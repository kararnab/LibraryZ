package recommendation

import (
	"github.com/kararnab/libraryZ/internal/catalog"
	"github.com/kararnab/libraryZ/internal/recommendation/recommender"
)

type Service struct {
	userRatings recommender.UserRatings
	allBooks    []catalog.Book
}

func NewService(userRatings recommender.UserRatings, allBooks []catalog.Book) *Service {
	return &Service{
		userRatings: userRatings,
		allBooks:    allBooks,
	}
}

func (s *Service) RecommendBooks(userID string) []string {
	// Collaborative Filtering
	collaborativeRecs := recommender.RecommendCollaborative(s.userRatings, userID)

	// Content-Based Filtering
	userBooks := s.getUserBooks(userID)
	contentBasedRecs := recommender.RecommendContentBased(userBooks, s.allBooks)

	// Combine and deduplicate recommendations
	finalRecs := combineRecommendations(collaborativeRecs, contentBasedRecs)

	return finalRecs
}

func (s *Service) getUserBooks(userID string) []catalog.Book {
	// Retrieve books from the user's history
	// This could be a query to a database
	return []catalog.Book{}
}

func combineRecommendations(collabRecs, contentRecs []string) []string {
	recMap := make(map[string]struct{})
	for _, rec := range append(collabRecs, contentRecs...) {
		recMap[rec] = struct{}{}
	}

	var finalRecs []string
	for rec := range recMap {
		finalRecs = append(finalRecs, rec)
	}

	return finalRecs
}
