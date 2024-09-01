package middleware

import (
	"context"
	"log"
	"net/http"
	"strings"

	"github.com/kararnab/libraryZ/pkg/utils"
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
			http.Error(w, "invalid or expired token: "+err.Error(), http.StatusUnauthorized)
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
