package auth

import (
	"errors"
	"strings"

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

type Service struct {
	db *gorm.DB
}

func NewService(db *gorm.DB) *Service {
	return &Service{db: db}
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

func (s *Service) Authenticate(email, password string) (string, error) {
	// Match the normalization applied at signup so case/whitespace variants
	// of the same address authenticate against the stored row.
	email = strings.TrimSpace(strings.ToLower(email))
	var user User
	err := s.db.Where("email = ?", email).First(&user).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		// Spend the bcrypt time anyway so the response timing matches the
		// wrong-password path, then return the same opaque error.
		utils.CheckPasswordHash(password, dummyHash)
		return "", ErrInvalidCredentials
	}
	if err != nil {
		return "", err
	}

	if !utils.CheckPasswordHash(password, user.Password) {
		return "", ErrInvalidCredentials
	}

	token, err := utils.GenerateJWT(user.ID)
	if err != nil {
		return "", err
	}

	return token, nil
}

func (s *Service) GetByID(id uint) (*User, error) {
	var u User
	if err := s.db.First(&u, "id = ?", id).Error; err != nil {
		return nil, err
	}
	return &u, nil
}
