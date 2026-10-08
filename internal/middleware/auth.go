package middleware

import (
	"context"
	"errors"
	"log"
	"net/http"
	"strings"

	"github.com/kararnab/libraryZ/pkg/utils"
	"gorm.io/gorm"
)

type ctxKey int

const userIDKey ctxKey = iota

// Auth verifies the Bearer access token, checks it hasn't been revoked, and
// injects the user id into the request context. Unauthenticated or revoked
// requests get 401.
//
// Revocation: the token's `tv` claim must equal the user's current
// users.token_version, so "log out everywhere" (or deleting the user) kills
// every outstanding access token immediately rather than at expiry. That
// costs one primary-key lookup per authenticated request.
func Auth(db *gorm.DB) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			authHeader := r.Header.Get("Authorization")
			if authHeader == "" {
				http.Error(w, "Authorization header is missing", http.StatusUnauthorized)
				return
			}
			token, ok := bearerToken(authHeader)
			if !ok {
				http.Error(w, "invalid Authorization header format", http.StatusUnauthorized)
				return
			}

			claims, err := utils.VerifyJWT(token)
			if err != nil {
				http.Error(w, "invalid or expired token", http.StatusUnauthorized)
				return
			}

			var row struct{ TokenVersion int }
			err = db.WithContext(r.Context()).
				Table("users").
				Select("token_version").
				Where("id = ?", claims.UserID).
				Take(&row).Error
			if errors.Is(err, gorm.ErrRecordNotFound) {
				http.Error(w, "invalid or expired token", http.StatusUnauthorized)
				return
			}
			if err != nil {
				log.Printf("%s %s: token version check failed: %v", r.Method, r.URL.Path, err)
				http.Error(w, "internal server error", http.StatusInternalServerError)
				return
			}
			if row.TokenVersion != claims.TokenVersion {
				http.Error(w, "token revoked", http.StatusUnauthorized)
				return
			}

			ctx := context.WithValue(r.Context(), userIDKey, claims.UserID)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

// bearerToken extracts the credential from an `Authorization: Bearer <token>`
// header. The scheme must be the first token and is matched case-insensitively
// (RFC 7235 §2.1); anything else — a different scheme, "Bearer" appearing
// mid-header, or an empty/whitespace-containing credential — is rejected.
func bearerToken(h string) (string, bool) {
	scheme, token, ok := strings.Cut(strings.TrimSpace(h), " ")
	if !ok || !strings.EqualFold(scheme, "Bearer") {
		return "", false
	}
	token = strings.TrimSpace(token)
	if token == "" || strings.ContainsAny(token, " \t") {
		return "", false
	}
	return token, true
}

// UserID extracts the authenticated user id injected by Auth.
func UserID(ctx context.Context) (uint, bool) {
	v, ok := ctx.Value(userIDKey).(uint)
	return v, ok
}

// Moderator gates a route on the caller's `is_moderator` flag. It must
// run *after* Auth (it reads the user id from context) and queries the
// users table directly via raw GORM to avoid importing internal/auth
// (which would couple every middleware consumer to that package).
// Returns 401 if the chain wasn't actually authenticated, 403 otherwise.
func Moderator(db *gorm.DB) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			uid, ok := UserID(r.Context())
			if !ok {
				http.Error(w, "unauthenticated", http.StatusUnauthorized)
				return
			}
			isMod, err := IsModerator(r.Context(), db, uid)
			if err != nil {
				log.Printf("%s %s: moderator check failed: %v", r.Method, r.URL.Path, err)
				http.Error(w, "internal server error", http.StatusInternalServerError)
				return
			}
			if !isMod {
				http.Error(w, "moderator required", http.StatusForbidden)
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

// IsModerator reports whether the user has the moderator flag. An unknown
// user is simply not a moderator.
func IsModerator(ctx context.Context, db *gorm.DB, userID uint) (bool, error) {
	var row struct{ IsModerator bool }
	err := db.WithContext(ctx).Table("users").Select("is_moderator").Where("id = ?", userID).Take(&row).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return false, nil
	}
	return row.IsModerator, err
}
