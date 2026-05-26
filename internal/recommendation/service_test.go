package recommendation_test

import (
	"context"
	"testing"

	"github.com/glebarez/sqlite"
	"github.com/google/uuid"
	"github.com/kararnab/libraryZ/internal/catalog"
	"github.com/kararnab/libraryZ/internal/library"
	"github.com/kararnab/libraryZ/internal/recommendation"
	"gorm.io/gorm"
)

func newTestDB(t *testing.T) *gorm.DB {
	t.Helper()
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	if err := catalog.Migrate(db); err != nil {
		t.Fatalf("catalog migrate: %v", err)
	}
	if err := library.Migrate(db); err != nil {
		t.Fatalf("library migrate: %v", err)
	}
	if err := recommendation.Migrate(db); err != nil {
		t.Fatalf("recommendation migrate: %v", err)
	}
	return db
}

// seedWork creates a work with the given authors + tag names.
func seedWork(t *testing.T, db *gorm.DB, title, authors string, tags ...string) uuid.UUID {
	t.Helper()
	w := catalog.Work{ID: uuid.New(), Title: title, Authors: authors}
	for _, name := range tags {
		w.Tags = append(w.Tags, catalog.Tag{ID: uuid.New(), Name: name})
	}
	if err := db.Create(&w).Error; err != nil {
		t.Fatalf("seed work %q: %v", title, err)
	}
	return w.ID
}

// addToLibrary inserts a user_books row directly (the rec service only reads).
func addToLibrary(t *testing.T, db *gorm.DB, userID uint, workID uuid.UUID, status string, rating *int) {
	t.Helper()
	ub := map[string]any{
		"id":      uuid.New(),
		"user_id": userID,
		"work_id": workID,
		"status":  status,
		"rating":  rating,
	}
	if err := db.Table("user_books").Create(ub).Error; err != nil {
		t.Fatalf("add to library: %v", err)
	}
}

func ratingPtr(i int) *int { return &i }

func ids(recs []recommendation.Recommendation) map[uuid.UUID]recommendation.Recommendation {
	m := make(map[uuid.UUID]recommendation.Recommendation, len(recs))
	for _, r := range recs {
		m[r.Work.ID] = r
	}
	return m
}

func TestRecommendContentPickSharedAuthorExcludesSeen(t *testing.T) {
	db := newTestDB(t)
	svc := recommendation.NewService(db)

	liked := seedWork(t, db, "Dune", "Frank Herbert", "sci-fi")
	sibling := seedWork(t, db, "Dune Messiah", "Frank Herbert", "sci-fi")
	unrelated := seedWork(t, db, "Pride and Prejudice", "Jane Austen", "romance")

	addToLibrary(t, db, 1, liked, "read", ratingPtr(5))

	recs, err := svc.Recommend(context.Background(), 1, 20)
	if err != nil {
		t.Fatalf("recommend: %v", err)
	}
	got := ids(recs)
	if _, ok := got[liked]; ok {
		t.Fatalf("the already-in-library work must be excluded")
	}
	if _, ok := got[sibling]; !ok {
		t.Fatalf("expected the shared-author/tag sibling to be recommended; got %+v", recs)
	}
	// The sibling is a content hit, not the popularity fallback.
	if got[sibling].Reason == "Popular on LibraryZ" {
		t.Fatalf("sibling should have a content reason, got %q", got[sibling].Reason)
	}
	// Unrelated work has no overlap, so it can only appear via fallback (after
	// the content hit) — and here it must rank below the sibling.
	if recs[0].Work.ID != sibling {
		t.Fatalf("expected sibling ranked first, got %s", recs[0].Work.Title)
	}
	_ = unrelated
}

func TestRecommendHigherRatedSeedOutranks(t *testing.T) {
	db := newTestDB(t)
	svc := recommendation.NewService(db)

	// Two liked authors with different ratings; each has one unseen sibling.
	likedHigh := seedWork(t, db, "A1", "Author High")
	likedLow := seedWork(t, db, "B1", "Author Low")
	candHigh := seedWork(t, db, "A2", "Author High")
	candLow := seedWork(t, db, "B2", "Author Low")

	addToLibrary(t, db, 1, likedHigh, "read", ratingPtr(5))
	addToLibrary(t, db, 1, likedLow, "read", ratingPtr(4))

	recs, err := svc.Recommend(context.Background(), 1, 20)
	if err != nil {
		t.Fatalf("recommend: %v", err)
	}
	// candHigh (author weighted 5) should rank above candLow (weighted 4).
	posHigh, posLow := -1, -1
	for i, r := range recs {
		if r.Work.ID == candHigh {
			posHigh = i
		}
		if r.Work.ID == candLow {
			posLow = i
		}
	}
	if posHigh == -1 || posLow == -1 {
		t.Fatalf("both candidates should appear; got %+v", recs)
	}
	if posHigh > posLow {
		t.Fatalf("higher-rated author's sibling should rank first (high=%d low=%d)", posHigh, posLow)
	}
}

func TestRecommendColdStartUsesPopularity(t *testing.T) {
	db := newTestDB(t)
	svc := recommendation.NewService(db)

	popular := seedWork(t, db, "Popular", "X")
	niche := seedWork(t, db, "Niche", "Y")
	// Three other users have `popular`; one has `niche`.
	addToLibrary(t, db, 10, popular, "want", nil)
	addToLibrary(t, db, 11, popular, "want", nil)
	addToLibrary(t, db, 12, popular, "want", nil)
	addToLibrary(t, db, 13, niche, "want", nil)

	// User 1 has an empty library → pure popularity fallback.
	recs, err := svc.Recommend(context.Background(), 1, 20)
	if err != nil {
		t.Fatalf("recommend: %v", err)
	}
	if len(recs) < 2 {
		t.Fatalf("expected both works recommended, got %d", len(recs))
	}
	if recs[0].Work.ID != popular {
		t.Fatalf("most-added work should rank first, got %s", recs[0].Work.Title)
	}
	if recs[0].Reason != "Popular on LibraryZ" {
		t.Fatalf("cold-start reason should be popular, got %q", recs[0].Reason)
	}
	_ = niche
}

func TestRecommendShortContentSetFilledByPopular(t *testing.T) {
	db := newTestDB(t)
	svc := recommendation.NewService(db)

	liked := seedWork(t, db, "Seed", "Solo Author", "uniquetag")
	sibling := seedWork(t, db, "Sibling", "Solo Author") // 1 content hit
	filler1 := seedWork(t, db, "Filler1", "Nobody")      // no overlap
	filler2 := seedWork(t, db, "Filler2", "Nobody2")     // no overlap
	addToLibrary(t, db, 1, liked, "read", ratingPtr(5))
	// Make filler1 popular so it leads the fallback tail.
	addToLibrary(t, db, 20, filler1, "want", nil)
	addToLibrary(t, db, 21, filler1, "want", nil)

	recs, err := svc.Recommend(context.Background(), 1, 20)
	if err != nil {
		t.Fatalf("recommend: %v", err)
	}
	if len(recs) < 3 {
		t.Fatalf("expected content hit + popular fill, got %d", len(recs))
	}
	// Content hit ranks first; fillers follow via the popularity fallback.
	if recs[0].Work.ID != sibling {
		t.Fatalf("content hit should lead, got %s", recs[0].Work.Title)
	}
	got := ids(recs)
	if got[sibling].Reason == "Popular on LibraryZ" {
		t.Fatalf("sibling should keep a content reason")
	}
	if _, ok := got[filler1]; !ok {
		t.Fatalf("popular filler should be present")
	}
	if got[filler1].Reason != "Popular on LibraryZ" {
		t.Fatalf("filler should carry the popular reason, got %q", got[filler1].Reason)
	}
	_ = filler2
}
