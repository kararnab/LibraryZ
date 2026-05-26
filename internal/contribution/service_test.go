package contribution_test

import (
	"context"
	"errors"
	"testing"

	"github.com/glebarez/sqlite"
	"github.com/google/uuid"
	"github.com/kararnab/libraryZ/internal/catalog"
	"github.com/kararnab/libraryZ/internal/contribution"
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
	if err := contribution.Migrate(db); err != nil {
		t.Fatalf("contribution migrate: %v", err)
	}
	return db
}

func seedWork(t *testing.T, db *gorm.DB, title, authors string) uuid.UUID {
	t.Helper()
	w := catalog.Work{ID: uuid.New(), Title: title, Authors: authors}
	if err := db.Create(&w).Error; err != nil {
		t.Fatalf("seed work: %v", err)
	}
	return w.ID
}

func TestSubmitAndListByStatus(t *testing.T) {
	db := newTestDB(t)
	svc := contribution.NewService(db)
	wid := seedWork(t, db, "Moby-Dick", "Melville")

	c, err := svc.Submit(context.Background(), wid, 1, map[string]any{"title": "Moby Dick"})
	if err != nil {
		t.Fatalf("submit: %v", err)
	}
	if c.Status != contribution.StatusPending {
		t.Fatalf("status=%s want pending", c.Status)
	}

	pending, err := svc.List(context.Background(), contribution.ListFilter{Status: contribution.StatusPending})
	if err != nil {
		t.Fatalf("list pending: %v", err)
	}
	if len(pending) != 1 || pending[0].ID != c.ID {
		t.Fatalf("list pending = %+v", pending)
	}

	approved, _ := svc.List(context.Background(), contribution.ListFilter{Status: contribution.StatusApproved})
	if len(approved) != 0 {
		t.Fatalf("list approved should be empty, got %+v", approved)
	}
}

func TestSubmitEmptyPatchReturnsErr(t *testing.T) {
	db := newTestDB(t)
	svc := contribution.NewService(db)
	wid := seedWork(t, db, "Foo", "")

	_, err := svc.Submit(context.Background(), wid, 1, map[string]any{})
	if !errors.Is(err, contribution.ErrEmptyPatch) {
		t.Fatalf("want ErrEmptyPatch, got %v", err)
	}
}

func TestSubmitUnknownWorkReturnsErr(t *testing.T) {
	db := newTestDB(t)
	svc := contribution.NewService(db)
	_, err := svc.Submit(context.Background(), uuid.New(), 1, map[string]any{"title": "x"})
	if !errors.Is(err, contribution.ErrWorkNotFound) {
		t.Fatalf("want ErrWorkNotFound, got %v", err)
	}
}

func TestApproveAppliesWhitelistedPatchAndDropsTheRest(t *testing.T) {
	db := newTestDB(t)
	svc := contribution.NewService(db)
	wid := seedWork(t, db, "Original", "old author")

	patch := map[string]any{
		"title":   "Revised",
		"authors": "new author",
		"bogus":   "junk",          // not whitelisted
		"id":      uuid.New().String(), // mustn't allow id rewrites
	}
	c, err := svc.Submit(context.Background(), wid, 1, patch)
	if err != nil {
		t.Fatalf("submit: %v", err)
	}

	out, err := svc.Approve(context.Background(), c.ID, 2)
	if err != nil {
		t.Fatalf("approve: %v", err)
	}
	if out.Status != contribution.StatusApproved {
		t.Fatalf("status=%s", out.Status)
	}
	if out.ReviewerID == nil || *out.ReviewerID != 2 {
		t.Fatalf("reviewer=%v", out.ReviewerID)
	}
	if out.DecidedAt == nil {
		t.Fatalf("decided_at not set")
	}

	var w catalog.Work
	if err := db.First(&w, "id = ?", wid).Error; err != nil {
		t.Fatal(err)
	}
	if w.ID != wid {
		t.Fatalf("id was rewritten: %s vs %s", w.ID, wid)
	}
	if w.Title != "Revised" {
		t.Fatalf("title not applied: %s", w.Title)
	}
	if w.Authors != "new author" {
		t.Fatalf("authors not applied: %s", w.Authors)
	}
}

func TestRejectDoesNotChangeWork(t *testing.T) {
	db := newTestDB(t)
	svc := contribution.NewService(db)
	wid := seedWork(t, db, "Original", "")

	c, _ := svc.Submit(context.Background(), wid, 1, map[string]any{"title": "Different"})
	out, err := svc.Reject(context.Background(), c.ID, 2)
	if err != nil {
		t.Fatalf("reject: %v", err)
	}
	if out.Status != contribution.StatusRejected {
		t.Fatalf("status=%s", out.Status)
	}

	var w catalog.Work
	if err := db.First(&w, "id = ?", wid).Error; err != nil {
		t.Fatal(err)
	}
	if w.Title != "Original" {
		t.Fatalf("title changed after reject: %s", w.Title)
	}
}

func TestDoubleApproveReturnsAlreadyDecided(t *testing.T) {
	db := newTestDB(t)
	svc := contribution.NewService(db)
	wid := seedWork(t, db, "Foo", "")

	c, _ := svc.Submit(context.Background(), wid, 1, map[string]any{"title": "Bar"})
	if _, err := svc.Approve(context.Background(), c.ID, 2); err != nil {
		t.Fatal(err)
	}
	if _, err := svc.Approve(context.Background(), c.ID, 2); !errors.Is(err, contribution.ErrAlreadyDecided) {
		t.Fatalf("want ErrAlreadyDecided, got %v", err)
	}
}

func TestApproveAfterRejectReturnsAlreadyDecided(t *testing.T) {
	db := newTestDB(t)
	svc := contribution.NewService(db)
	wid := seedWork(t, db, "Foo", "")

	c, _ := svc.Submit(context.Background(), wid, 1, map[string]any{"title": "Bar"})
	if _, err := svc.Reject(context.Background(), c.ID, 2); err != nil {
		t.Fatal(err)
	}
	if _, err := svc.Approve(context.Background(), c.ID, 2); !errors.Is(err, contribution.ErrAlreadyDecided) {
		t.Fatalf("want ErrAlreadyDecided, got %v", err)
	}
}

func TestListPopulatesContributorName(t *testing.T) {
	db := newTestDB(t)
	// Seed a users table that matches what auth.Migrate would have created.
	if err := db.Exec(`CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, name TEXT)`).Error; err != nil {
		t.Fatalf("create users: %v", err)
	}
	if err := db.Exec(`INSERT INTO users (id, name) VALUES (7, 'Mira K')`).Error; err != nil {
		t.Fatalf("seed user: %v", err)
	}
	svc := contribution.NewService(db)
	wid := seedWork(t, db, "Foo", "")

	if _, err := svc.Submit(context.Background(), wid, 7, map[string]any{"title": "Bar"}); err != nil {
		t.Fatalf("submit: %v", err)
	}

	cs, err := svc.List(context.Background(), contribution.ListFilter{})
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(cs) != 1 || cs[0].ContributorName != "Mira K" {
		t.Fatalf("expected ContributorName='Mira K', got %+v", cs)
	}
}

func TestListPopulatesCurrentValuesFromWork(t *testing.T) {
	db := newTestDB(t)
	svc := contribution.NewService(db)
	// Seed a work with known current values for the fields we'll patch.
	wid := seedWork(t, db, "Old Title", "Old Author")
	if err := db.Table("works").Where("id = ?", wid).
		Update("publication_year", 1975).Error; err != nil {
		t.Fatalf("seed year: %v", err)
	}

	if _, err := svc.Submit(context.Background(), wid, 1, map[string]any{
		"title":            "New Title",
		"publication_year": 1995,
	}); err != nil {
		t.Fatalf("submit: %v", err)
	}

	cs, err := svc.List(context.Background(), contribution.ListFilter{})
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(cs) != 1 {
		t.Fatalf("expected 1 contribution, got %d", len(cs))
	}
	cur := cs[0].Current
	if cur == nil {
		t.Fatalf("Current not populated")
	}
	// Only patched fields appear in Current, with the Work's present values.
	if got := cur["title"]; got != "Old Title" {
		t.Fatalf("current[title] = %v, want 'Old Title'", got)
	}
	// JSON round-trip isn't involved here (in-process), so the int stays int.
	if got, ok := cur["publication_year"].(int); !ok || got != 1975 {
		t.Fatalf("current[publication_year] = %v (%T), want 1975", cur["publication_year"], cur["publication_year"])
	}
	if _, present := cur["authors"]; present {
		t.Fatalf("Current should only hold patched fields, but has 'authors'")
	}
}

func TestApproveOpenLibraryIDMapsToColumn(t *testing.T) {
	// Regression: the patch key is "openlibrary_id" but the works column is
	// "open_library_id". Approving must translate, not 500 on a missing col.
	db := newTestDB(t)
	svc := contribution.NewService(db)
	wid := seedWork(t, db, "Foo", "")

	c, err := svc.Submit(context.Background(), wid, 1, map[string]any{
		"openlibrary_id": "OL12345W",
	})
	if err != nil {
		t.Fatalf("submit: %v", err)
	}
	if _, err := svc.Approve(context.Background(), c.ID, 2); err != nil {
		t.Fatalf("approve openlibrary_id patch: %v", err)
	}

	var got struct{ OpenLibraryID string }
	if err := db.Table("works").Select("open_library_id").
		Where("id = ?", wid).Scan(&got).Error; err != nil {
		t.Fatalf("read work: %v", err)
	}
	if got.OpenLibraryID != "OL12345W" {
		t.Fatalf("open_library_id not applied: %q", got.OpenLibraryID)
	}
}

func TestListByContributor(t *testing.T) {
	db := newTestDB(t)
	svc := contribution.NewService(db)
	wid := seedWork(t, db, "Foo", "")

	_, _ = svc.Submit(context.Background(), wid, 1, map[string]any{"title": "A"})
	_, _ = svc.Submit(context.Background(), wid, 2, map[string]any{"title": "B"})

	uid := uint(1)
	mine, err := svc.List(context.Background(), contribution.ListFilter{ContributorID: &uid})
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(mine) != 1 || mine[0].ContributorID != 1 {
		t.Fatalf("expected only user 1's submissions, got %+v", mine)
	}
}
