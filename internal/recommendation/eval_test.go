//go:build eval

package recommendation_test

import (
	"context"
	"testing"

	"github.com/google/uuid"
	"github.com/kararnab/libraryZ/internal/recommendation"
)

// Guardrail: on a planted multi-cluster dataset where users have *disjoint*
// tastes (each user likes exactly one cluster), MF should recover held-out
// items better than ranking by global popularity — popularity can't tell which
// cluster a given user belongs to. Run with: go test -tags=eval ./internal/recommendation/...
func TestMFBeatsPopularityBaseline(t *testing.T) {
	db := newTestDB(t)

	// 4 clusters × 5 works = 20 works; 8 users per cluster each like all 5.
	const clusters, perCluster, usersPerCluster = 4, 5, 8
	works := make([][]uuid.UUID, clusters)
	uid := uint(1)
	for c := 0; c < clusters; c++ {
		for w := 0; w < perCluster; w++ {
			works[c] = append(works[c], seedWork(t, db, "C", "Author"))
		}
	}
	for c := 0; c < clusters; c++ {
		for u := 0; u < usersPerCluster; u++ {
			for _, w := range works[c] {
				addToLibrary(t, db, uid, w, "read", ratingPtr(5))
			}
			uid++
		}
	}

	res, err := recommendation.Evaluate(
		context.Background(), db, recommendation.DefaultConfig(),
		10 /*K*/, 0.4 /*holdout*/, 7 /*seed*/)
	if err != nil {
		t.Fatalf("evaluate: %v", err)
	}
	t.Logf("users=%d  MF hit@%d=%.3f prec=%.3f  |  pop hit@%d=%.3f prec=%.3f",
		res.Users, res.K, res.MFHitRate, res.MFPrecision, res.K, res.PopHitRate, res.PopPrecision)

	if res.Users == 0 {
		t.Fatalf("no users evaluated")
	}
	if !(res.MFHitRate > res.PopHitRate) {
		t.Fatalf("expected MF hit-rate (%.3f) > popularity baseline (%.3f)", res.MFHitRate, res.PopHitRate)
	}
}
