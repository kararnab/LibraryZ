package recommender

import (
	"github.com/kararnab/libraryZ/internal/catalog"
	"math"
	"sort"
	"strings"
)

func CosineSimilarity(vec1, vec2 map[string]float64) float64 {
	var dotProduct, normA, normB float64
	for key, valA := range vec1 {
		if valB, ok := vec2[key]; ok {
			dotProduct += valA * valB
		}
		normA += valA * valA
	}
	for _, valB := range vec2 {
		normB += valB * valB
	}
	return dotProduct / (math.Sqrt(normA) * math.Sqrt(normB))
}

func BuildFeatureVector(book catalog.Book) map[string]float64 {
	vector := map[string]float64{}
	for _, keyword := range append(book.Keywords, strings.ToLower(book.Title), strings.ToLower(book.Genre), strings.ToLower(book.Author)) {
		vector[keyword]++
	}
	return vector
}

func RecommendContentBased(userBooks []catalog.Book, allBooks []catalog.Book) []string {
	userProfile := map[string]float64{}
	for _, book := range userBooks {
		bookVector := BuildFeatureVector(book)
		for key, val := range bookVector {
			userProfile[key] += val
		}
	}

	var rankings []struct {
		BookID string
		Score  float64
	}
	for _, book := range allBooks {
		if contains(userBooks, book) {
			continue
		}
		bookVector := BuildFeatureVector(book)
		similarity := CosineSimilarity(userProfile, bookVector)
		rankings = append(rankings, struct {
			BookID string
			Score  float64
		}{BookID: book.ID, Score: similarity})
	}

	sort.Slice(rankings, func(i, j int) bool {
		return rankings[i].Score > rankings[j].Score
	})

	var recommendations []string
	for _, ranking := range rankings {
		recommendations = append(recommendations, ranking.BookID)
	}

	return recommendations
}

func contains(books []catalog.Book, target catalog.Book) bool {
	for _, book := range books {
		if book.ID == target.ID {
			return true
		}
	}
	return false
}
