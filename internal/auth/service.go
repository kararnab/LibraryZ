package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"log"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/kararnab/libraryZ/pkg/utils"
	"gorm.io/gorm"
)

// ErrInvalidCredentials is returned for both unknown emails and wrong
// passwords so callers can't distinguish the two (no user enumeration).
var ErrInvalidCredentials = errors.New("invalid credentials")

// Signup validation sentinels. The handler maps these to 4xx with the
// (safe, user-facing) message; anything else from CreateUser is a 500.
var (
	ErrInvalidEmail = errors.New("a valid email is required")
	ErrWeakPassword = errors.New("password must be at least 8 characters")
	ErrEmailExists  = errors.New("email already registered")
)

// minPasswordLen is a floor, not a policy — bcrypt also caps input at 72
// bytes, but that's well above anything a signup form should reject.
const minPasswordLen = 8

// dummyHash is a valid bcrypt hash compared against on the user-not-found
// path so that branch spends the same ~bcrypt time as a real check. Without
// it, "unknown email" returns near-instantly while "wrong password" pays for
// bcrypt — a timing side channel that leaks which emails are registered.
var dummyHash, _ = utils.HashPassword("timing-equalizer-not-a-real-password")

// ErrInvalidRefreshToken covers unknown, expired, revoked and reused refresh
// tokens alike — the client's only recourse is to log in again.
var ErrInvalidRefreshToken = errors.New("invalid refresh token")

// DefaultRefreshTokenTTL is how long a refresh token stays usable if the
// client never rotates it.
const DefaultRefreshTokenTTL = 30 * 24 * time.Hour

// TokenPair is what login and refresh hand back.
type TokenPair struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	TokenType    string `json:"token_type"`
	// ExpiresIn is the access token's lifetime in seconds.
	ExpiresIn int `json:"expires_in"`
}

type Service struct {
	db         *gorm.DB
	accessTTL  time.Duration
	refreshTTL time.Duration
}

// NewService builds the auth service. Zero TTLs mean the defaults
// (utils.DefaultAccessTokenTTL, DefaultRefreshTokenTTL).
func NewService(db *gorm.DB, accessTTL, refreshTTL time.Duration) *Service {
	if accessTTL <= 0 {
		accessTTL = utils.DefaultAccessTokenTTL
	}
	if refreshTTL <= 0 {
		refreshTTL = DefaultRefreshTokenTTL
	}
	return &Service{db: db, accessTTL: accessTTL, refreshTTL: refreshTTL}
}

func (s *Service) CreateUser(user User) error {
	email := strings.TrimSpace(strings.ToLower(user.Email))
	if !validEmail(email) {
		return ErrInvalidEmail
	}
	if len(user.Password) < minPasswordLen {
		return ErrWeakPassword
	}

	// Pre-check keeps the common duplicate case a clean 409 across both
	// dialects (sqlite tests + Postgres) without depending on driver-specific
	// error translation. The unique index on email is still the source of
	// truth: a concurrent signup that loses the race surfaces as a generic
	// 500, which is acceptable for that rare window.
	var count int64
	if err := s.db.Model(&User{}).Where("email = ?", email).Count(&count).Error; err != nil {
		return err
	}
	if count > 0 {
		return ErrEmailExists
	}

	hashedPassword, err := utils.HashPassword(user.Password)
	if err != nil {
		return err
	}

	u := User{Email: email, Password: hashedPassword, Name: user.Name}
	return s.db.Create(&u).Error
}

// validEmail is a deliberately minimal sanity check (non-empty, a single "@"
// with text on both sides, no spaces) — not RFC 5322. Real deliverability is
// proven by a confirmation email, which is out of scope here.
func validEmail(email string) bool {
	if email == "" || strings.ContainsAny(email, " \t\r\n") {
		return false
	}
	at := strings.IndexByte(email, '@')
	if at <= 0 || at != strings.LastIndexByte(email, '@') || at == len(email)-1 {
		return false
	}
	return strings.Contains(email[at+1:], ".")
}

// Authenticate checks credentials and starts a new session (a fresh
// refresh-token family).
func (s *Service) Authenticate(email, password string) (*TokenPair, error) {
	// Match the normalization applied at signup so case/whitespace variants
	// of the same address authenticate against the stored row.
	email = strings.TrimSpace(strings.ToLower(email))
	var user User
	err := s.db.Where("email = ?", email).First(&user).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		// Spend the bcrypt time anyway so the response timing matches the
		// wrong-password path, then return the same opaque error.
		utils.CheckPasswordHash(password, dummyHash)
		return nil, ErrInvalidCredentials
	}
	if err != nil {
		return nil, err
	}

	if !utils.CheckPasswordHash(password, user.Password) {
		return nil, ErrInvalidCredentials
	}

	var pair *TokenPair
	err = s.db.Transaction(func(tx *gorm.DB) error {
		var err error
		pair, err = s.issue(tx, &user, uuid.New())
		return err
	})
	return pair, err
}

// Refresh rotates a refresh token: the presented one is spent and a new
// access + refresh pair (same family) is returned.
//
// Reuse detection: if the presented token was already spent, someone is
// replaying it — either an attacker who stole it or the legitimate client
// after an attacker already rotated it. We can't tell which, so the whole
// family is revoked and both must log in again.
func (s *Service) Refresh(refreshToken string) (*TokenPair, error) {
	hash := hashToken(refreshToken)
	var pair *TokenPair
	var reused *RefreshToken
	err := s.db.Transaction(func(tx *gorm.DB) error {
		var rt RefreshToken
		if err := tx.Where("token_hash = ?", hash).First(&rt).Error; err != nil {
			if errors.Is(err, gorm.ErrRecordNotFound) {
				return ErrInvalidRefreshToken
			}
			return err
		}
		if rt.RevokedAt != nil || time.Now().After(rt.ExpiresAt) {
			return ErrInvalidRefreshToken
		}
		// Spend it atomically: of two concurrent refreshes with the same
		// token, exactly one wins; the other is treated as reuse.
		now := time.Now()
		res := tx.Model(&RefreshToken{}).
			Where("id = ? AND used_at IS NULL AND revoked_at IS NULL", rt.ID).
			Update("used_at", now)
		if res.Error != nil {
			return res.Error
		}
		if res.RowsAffected == 0 {
			reused = &rt
			return ErrInvalidRefreshToken
		}

		var user User
		if err := tx.First(&user, "id = ?", rt.UserID).Error; err != nil {
			if errors.Is(err, gorm.ErrRecordNotFound) {
				return ErrInvalidRefreshToken
			}
			return err
		}
		var err error
		pair, err = s.issue(tx, &user, rt.FamilyID)
		return err
	})
	if reused != nil {
		// Outside the failed transaction, which rolled back.
		if err := s.revokeFamily(reused.FamilyID); err != nil {
			return nil, err
		}
		log.Printf("auth: refresh token reuse for user %d; revoked family %s", reused.UserID, reused.FamilyID)
	}
	return pair, err
}

// Logout revokes the session the refresh token belongs to. Unknown tokens
// are not an error — the client is logged out either way.
func (s *Service) Logout(refreshToken string) error {
	var rt RefreshToken
	err := s.db.Where("token_hash = ?", hashToken(refreshToken)).First(&rt).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil
	}
	if err != nil {
		return err
	}
	return s.revokeFamily(rt.FamilyID)
}

// LogoutAll ends every session of the user: bumping token_version
// invalidates all outstanding access tokens (the middleware checks it), and
// all refresh tokens are revoked.
func (s *Service) LogoutAll(userID uint) error {
	return s.db.Transaction(func(tx *gorm.DB) error {
		if err := tx.Model(&User{}).Where("id = ?", userID).
			Update("token_version", gorm.Expr("token_version + 1")).Error; err != nil {
			return err
		}
		return tx.Model(&RefreshToken{}).
			Where("user_id = ? AND revoked_at IS NULL", userID).
			Update("revoked_at", time.Now()).Error
	})
}

func (s *Service) revokeFamily(family uuid.UUID) error {
	return s.db.Model(&RefreshToken{}).
		Where("family_id = ? AND revoked_at IS NULL", family).
		Update("revoked_at", time.Now()).Error
}

// issue mints an access token and a new refresh token in the given family.
func (s *Service) issue(tx *gorm.DB, user *User, family uuid.UUID) (*TokenPair, error) {
	access, err := utils.GenerateJWT(user.ID, user.TokenVersion, s.accessTTL)
	if err != nil {
		return nil, err
	}
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return nil, err
	}
	refresh := base64.RawURLEncoding.EncodeToString(raw)
	if err := tx.Create(&RefreshToken{
		ID:        uuid.New(),
		UserID:    user.ID,
		FamilyID:  family,
		TokenHash: hashToken(refresh),
		ExpiresAt: time.Now().Add(s.refreshTTL),
	}).Error; err != nil {
		return nil, err
	}
	return &TokenPair{
		AccessToken:  access,
		RefreshToken: refresh,
		TokenType:    "Bearer",
		ExpiresIn:    int(s.accessTTL / time.Second),
	}, nil
}

// hashToken is SHA-256, not bcrypt: refresh tokens are 256 random bits, so
// there's nothing to brute-force, and the lookup must be by hash.
func hashToken(t string) string {
	sum := sha256.Sum256([]byte(t))
	return hex.EncodeToString(sum[:])
}

func (s *Service) GetByID(id uint) (*User, error) {
	var u User
	if err := s.db.First(&u, "id = ?", id).Error; err != nil {
		return nil, err
	}
	return &u, nil
}
