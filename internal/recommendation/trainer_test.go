package recommendation_test

import (
	"context"
	"encoding/json"
	"math"
	"testing"

	"github.com/google/uuid"
	"github.com/kararnab/libraryZ/internal/recommendation"
	"gorm.io/gorm"
)

func cosine(a, b []float64) float64 {
	var dot, na, nb float64
	for i := range a {
		dot += a[i] * b[i]
		na += a[i] * a[i]
		nb += b[i] * b[i]
	}
	if na == 0 || nb == 0 {
		return 0
	}
	return dot / (math.Sqrt(na) * math.Sqrt(nb))
}

func loadWorkVector(t *testing.T, db *gorm.DB, id uuid.UUID) []float64 {
	t.Helper()
	var wf recommendation.WorkFactors
	if err := db.First(&wf, "work_id = ?", id).Error; err != nil {
		t.Fatalf("load work factors %s: %v", id, err)
	}
	var v []float64
	if err := json.Unmarshal(wf.Vector, &v); err != nil {
		t.Fatalf("unmarshal vector: %v", err)
	}
	return v
}

func loadUserVector(t *testing.T, db *gorm.DB, id uint) []float64 {
	t.Helper()
	var uf recommendation.UserFactors
	if err := db.First(&uf, "user_id = ?", id).Error; err != nil {
		t.Fatalf("load user factors %d: %v", id, err)
	}
	var v []float64
	if err := json.Unmarshal(uf.Vector, &v); err != nil {
		t.Fatalf("unmarshal vector: %v", err)
	}
	return v
}

// Plant two clusters: users 1–6 read works A0–A2, users 7–12 read works B0–B2.
// After ALS the within-cluster work vectors should be more similar than
// across-cluster, and a cluster-A user's vector should score A works above B.
func TestTrainClustersWorkAndUserVectors(t *testing.T) {
	db := newTestDB(t)

	a := []uuid.UUID{
		seedWork(t, db, "A0", "AuthA"), seedWork(t, db, "A1", "AuthA"), seedWork(t, db, "A2", "AuthA"),
	}
	b := []uuid.UUID{
		seedWork(t, db, "B0", "AuthB"), seedWork(t, db, "B1", "AuthB"), seedWork(t, db, "B2", "AuthB"),
	}
	for u := uint(1); u <= 6; u++ {
		for _, w := range a {
			addToLibrary(t, db, u, w, "read", ratingPtr(5))
		}
	}
	for u := uint(7); u <= 12; u++ {
		for _, w := range b {
			addToLibrary(t, db, u, w, "read", ratingPtr(5))
		}
	}

	tr := recommendation.NewTrainer(db, recommendation.DefaultConfig())
	if err := tr.Train(context.Background()); err != nil {
		t.Fatalf("train: %v", err)
	}

	// Work vectors cluster: A0~A1 should beat A0~B0.
	va0, va1, vb0 := loadWorkVector(t, db, a[0]), loadWorkVector(t, db, a[1]), loadWorkVector(t, db, b[0])
	intra, cross := cosine(va0, va1), cosine(va0, vb0)
	if intra <= cross {
		t.Fatalf("expected intra-cluster cosine (%.3f) > cross-cluster (%.3f)", intra, cross)
	}

	// Top neighbor of A0 is a cluster-A work.
	var neighbors []recommendation.WorkNeighbor
	if err := db.Where("work_id = ?", a[0]).Order("score DESC").Find(&neighbors).Error; err != nil {
		t.Fatalf("load neighbors: %v", err)
	}
	if len(neighbors) == 0 {
		t.Fatalf("expected neighbors for A0")
	}
	if top := neighbors[0].NeighborID; top != a[1] && top != a[2] {
		t.Fatalf("A0's top neighbor %s should be a cluster-A mate", top)
	}

	// A cluster-A user scores A works above B works.
	uVec := loadUserVector(t, db, 1)
	scoreA := dotTest(uVec, va0)
	scoreB := dotTest(uVec, vb0)
	if scoreA <= scoreB {
		t.Fatalf("user 1 should score A work (%.3f) above B work (%.3f)", scoreA, scoreB)
	}
}

func dotTest(a, b []float64) float64 {
	var s float64
	for i := range a {
		s += a[i] * b[i]
	}
	return s
}

func TestTrainEmptyCorpusClearsTables(t *testing.T) {
	db := newTestDB(t)
	// Pre-seed a stale factor row to ensure Train wipes it.
	seedWork(t, db, "Orphan", "X")
	if err := db.Create(&recommendation.WorkFactors{WorkID: uuid.New(), Vector: []byte("[0.1]")}).Error; err != nil {
		t.Fatalf("seed stale factors: %v", err)
	}
	tr := recommendation.NewTrainer(db, recommendation.DefaultConfig())
	if err := tr.Train(context.Background()); err != nil {
		t.Fatalf("train empty: %v", err)
	}
	var n int64
	db.Model(&recommendation.WorkFactors{}).Count(&n)
	if n != 0 {
		t.Fatalf("empty-corpus train should clear factor tables, got %d rows", n)
	}
}
