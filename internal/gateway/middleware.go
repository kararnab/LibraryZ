package gateway

import (
	"github.com/kararnab/libraryZ/pkg/utils"
	"log"
	"net/http"
	"strings"
)

// AuthMiddleware checks for a valid JWT token before processing the request
func AuthMiddleware(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		authHeader := r.Header.Get("Authorization")
		if authHeader == "" {
			http.Error(w, "Authorization header is missing", http.StatusUnauthorized)
			return
		}

		// Ensure the "Bearer " prefix is present
		parts := strings.Split(authHeader, "Bearer ")
		if len(parts) != 2 {
			http.Error(w, "Invalid Authorization header format", http.StatusUnauthorized)
			return
		}

		tokenString := parts[1]

		// Use VerifyJWT from the utils package to verify the token
		claims, err := utils.VerifyJWT(tokenString)
		if err != nil {
			http.Error(w, "Invalid or expired token: "+err.Error(), http.StatusUnauthorized)
			return
		}

		// Token is valid; proceed with the request
		log.Printf("Authenticated user: %v", claims["sub"])
		next.ServeHTTP(w, r)
	}
}
