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
	// Several holdout draws, not one: a single seed passed or failed by luck
	// while the model was under-regularized, which hid the problem.
	const seeds = 10
	var mfTotal float64
	for seed := int64(1); seed <= seeds; seed++ {
		res := evaluatePlantedClusters(t, seed)
		t.Logf("seed %2d  users=%d  MF hit@%d=%.3f prec=%.3f  |  pop hit@%d=%.3f prec=%.3f",
			seed, res.Users, res.K, res.MFHitRate, res.MFPrecision, res.K, res.PopHitRate, res.PopPrecision)
		if res.Users == 0 {
			t.Fatalf("seed %d: no users evaluated", seed)
		}
		if !(res.MFHitRate > res.PopHitRate) {
			t.Errorf("seed %d: expected MF hit-rate (%.3f) > popularity baseline (%.3f)", seed, res.MFHitRate, res.PopHitRate)
		}
		mfTotal += res.MFHitRate
	}
	// Each user's held-out items are exactly the unseen works of their own
	// cluster, so a working model recovers nearly all of them.
	if mean := mfTotal / seeds; mean < 0.9 {
		t.Fatalf("mean MF hit-rate %.3f < 0.9 — the model isn't recovering planted clusters", mean)
	}
}

// evaluatePlantedClusters builds 4 clusters × 5 works with 8 users per cluster
// who each like their whole cluster, then runs the holdout evaluation.
func evaluatePlantedClusters(t *testing.T, seed int64) recommendation.EvalResult {
	t.Helper()
	db := newTestDB(t)
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
		10 /*K*/, 0.4 /*holdout*/, seed)
	if err != nil {
		t.Fatalf("evaluate: %v", err)
	}
	return res
}
