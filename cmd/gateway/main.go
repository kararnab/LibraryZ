package main

import (
	"github.com/gorilla/mux"
	gateway2 "github.com/kararnab/libraryZ/internal/gateway"
	"github.com/kararnab/libraryZ/pkg/config"
	"log"
	"net/http"
)

func main() {
	port := config.GetGatewayPort()
	if port == "" {
		log.Fatal("GATEWAY_PORT environment variable is not set")
	}

	r := mux.NewRouter()

	// Register routes
	r.HandleFunc("/auth/signup", gateway2.SignUpHandler).Methods(http.MethodPost)
	r.HandleFunc("/recommendations", gateway2.AuthMiddleware(gateway2.RecommendationHandler)).Methods(http.MethodGet)

	log.Printf("Gateway API is running on port %s", port)
	if err := http.ListenAndServe(":"+port, r); err != nil {
		log.Fatalf("Failed to start server: %v", err)
	}
}
