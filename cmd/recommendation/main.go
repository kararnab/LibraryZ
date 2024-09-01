package main

import (
	"github.com/kararnab/libraryZ/internal/catalog"
	"github.com/kararnab/libraryZ/internal/recommendation"
	"github.com/kararnab/libraryZ/internal/recommendation/recommender"
	"github.com/kararnab/libraryZ/pkg/config"
	"log"
	"net/http"
)

func main() {
	cfg := config.LoadAllConfig()

	userRatings := make(recommender.UserRatings) // Mocked user ratings for collaborative filtering
	var allBooks []catalog.Book                  // Mocked list of all books

	recommendationService := recommendation.NewService(userRatings, allBooks)
	handler := recommendation.NewHandler(recommendationService)

	http.HandleFunc("/recommendations", handler.GetRecommendations)

	log.Printf("Starting Recommendation Service on %s...", cfg.RecommendationServicePort)
	log.Fatal(http.ListenAndServe(cfg.RecommendationServicePort, nil))
}
