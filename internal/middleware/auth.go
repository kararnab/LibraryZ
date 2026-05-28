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

// Auth verifies the Bearer JWT, injects the user id into the request context,
// and rejects unauthenticated requests with 401.
func Auth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		authHeader := r.Header.Get("Authorization")
		if authHeader == "" {
			http.Error(w, "Authorization header is missing", http.StatusUnauthorized)
			return
		}
		parts := strings.SplitN(authHeader, "Bearer ", 2)
		if len(parts) != 2 || parts[1] == "" {
			http.Error(w, "invalid Authorization header format", http.StatusUnauthorized)
			return
		}

		claims, err := utils.VerifyJWT(parts[1])
		if err != nil {
			http.Error(w, "invalid or expired token", http.StatusUnauthorized)
			return
		}

		raw, ok := claims["user_id"].(float64)
		if !ok {
			log.Printf("auth middleware: user_id claim missing or wrong type: %T", claims["user_id"])
			http.Error(w, "invalid token claims", http.StatusUnauthorized)
			return
		}
		ctx := context.WithValue(r.Context(), userIDKey, uint(raw))
		next.ServeHTTP(w, r.WithContext(ctx))
	})
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
			var row struct {
				IsModerator bool
			}
			err := db.WithContext(r.Context()).
				Table("users").
				Select("is_moderator").
				Where("id = ?", uid).
				Take(&row).Error
			if errors.Is(err, gorm.ErrRecordNotFound) {
				http.Error(w, "forbidden", http.StatusForbidden)
				return
			}
			if err != nil {
				log.Printf("%s %s: moderator check failed: %v", r.Method, r.URL.Path, err)
				http.Error(w, "internal server error", http.StatusInternalServerError)
				return
			}
			if !row.IsModerator {
				http.Error(w, "moderator required", http.StatusForbidden)
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}
