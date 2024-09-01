package recommender

import (
	"math"
	"sort"
)

type UserRatings map[string]map[string]float64 // map[userID]map[bookID]rating

func SimilarityScore(user1, user2 map[string]float64) float64 {
	var sumSquares float64
	for book, rating1 := range user1 {
		if rating2, ok := user2[book]; ok {
			sumSquares += math.Pow(rating1-rating2, 2)
		}
	}
	return 1 / (1 + math.Sqrt(sumSquares))
}

func RecommendCollaborative(userRatings UserRatings, userID string) []string {
	scores := map[string]float64{}
	totalSim := map[string]float64{}

	for otherUser, ratings := range userRatings {
		if otherUser == userID {
			continue
		}
		similarity := SimilarityScore(userRatings[userID], ratings)
		if similarity <= 0 {
			continue
		}

		for book, rating := range ratings {
			if _, ok := userRatings[userID][book]; !ok {
				scores[book] += rating * similarity
				totalSim[book] += similarity
			}
		}
	}

	var rankings []struct {
		BookID string
		Score  float64
	}
	for book, score := range scores {
		rankings = append(rankings, struct {
			BookID string
			Score  float64
		}{BookID: book, Score: score / totalSim[book]})
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
