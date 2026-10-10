package utils

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/kararnab/libraryZ/pkg/config"
	"golang.org/x/crypto/bcrypt"
)

// DefaultAccessTokenTTL is how long an access token lives. Short on purpose:
// access tokens can't be revoked individually, so their lifetime bounds how
// long a leaked one is useful. Clients renew via the refresh token.
const DefaultAccessTokenTTL = 15 * time.Minute

// keyring holds the HMAC secrets tokens are verified against, by key id. The
// current secret signs; the previous one (JWT_SECRET_PREVIOUS) only verifies,
// so a secret can be rotated with a rolling restart instead of logging
// everyone out: deploy with the new JWT_SECRET and the old one as previous,
// and drop previous once the old access tokens have expired.
type keyring struct {
	signKID string
	keys    map[string][]byte
}

var keys = newKeyring(config.GetJWTSecret(), config.GetJWTPreviousSecret())

func newKeyring(current, previous string) keyring {
	k := keyring{signKID: keyID(current), keys: map[string][]byte{keyID(current): []byte(current)}}
	if previous != "" {
		k.keys[keyID(previous)] = []byte(previous)
	}
	return k
}

// keyID derives a stable, non-secret identifier for a secret so the `kid`
// header can name the key without any extra configuration.
func keyID(secret string) string {
	sum := sha256.Sum256([]byte("libraryz-kid:" + secret))
	return hex.EncodeToString(sum[:4])
}

// AccessClaims are the claims an access token carries.
type AccessClaims struct {
	UserID uint `json:"user_id"`
	// TokenVersion must match users.token_version; bumping the column
	// invalidates every outstanding access token for that user ("log out
	// everywhere").
	TokenVersion int `json:"tv"`
	jwt.RegisteredClaims
}

// GenerateJWT issues an access token for the user at the given token version.
func GenerateJWT(userID uint, tokenVersion int, ttl time.Duration) (string, error) {
	if ttl <= 0 {
		ttl = DefaultAccessTokenTTL
	}
	now := time.Now()
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, AccessClaims{
		UserID:       userID,
		TokenVersion: tokenVersion,
		RegisteredClaims: jwt.RegisteredClaims{
			IssuedAt:  jwt.NewNumericDate(now),
			ExpiresAt: jwt.NewNumericDate(now.Add(ttl)),
		},
	})
	token.Header["kid"] = keys.signKID
	return token.SignedString(keys.keys[keys.signKID])
}

// VerifyJWT checks signature, algorithm (exactly HS256), key id, and expiry
// (which must be present), and returns the claims.
func VerifyJWT(tokenString string) (*AccessClaims, error) {
	claims := &AccessClaims{}
	token, err := jwt.ParseWithClaims(tokenString, claims, func(token *jwt.Token) (interface{}, error) {
		kid, _ := token.Header["kid"].(string)
		key, ok := keys.keys[kid]
		if !ok {
			return nil, errors.New("unknown signing key")
		}
		return key, nil
	}, jwt.WithValidMethods([]string{jwt.SigningMethodHS256.Alg()}), jwt.WithExpirationRequired())
	if err != nil {
		return nil, err
	}
	if !token.Valid || claims.UserID == 0 {
		return nil, errors.New("invalid token")
	}
	return claims, nil
}

func CheckPasswordHash(password, hashedPassword string) bool {
	err := bcrypt.CompareHashAndPassword([]byte(hashedPassword), []byte(password))
	return err == nil
}

func HashPassword(password string) (string, error) {
	hashedPassword, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return "", err
	}
	return string(hashedPassword), nil
}
