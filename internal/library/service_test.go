package library_test

import (
	"context"
	"errors"
	"testing"

	"github.com/glebarez/sqlite"
	"github.com/google/uuid"
	"github.com/kararnab/libraryZ/internal/catalog"
	"github.com/kararnab/libraryZ/internal/library"
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

func strptr(s string) *string { return &s }
func intptr(i int) *int       { return &i }

func TestUpsertCreatesWithDefaults(t *testing.T) {
	db := newTestDB(t)
	svc := library.NewService(db)
	wid := seedWork(t, db, "Dune", "Herbert")

	ub, err := svc.Upsert(context.Background(), 1, wid, library.UpsertRequest{})
	if err != nil {
		t.Fatalf("upsert: %v", err)
	}
	if ub.Status != library.StatusWant {
		t.Fatalf("default status = %s, want want", ub.Status)
	}
	if ub.UserID != 1 || ub.WorkID != wid {
		t.Fatalf("identity wrong: %+v", ub)
	}
	if ub.Work == nil || ub.Work.Title != "Dune" {
		t.Fatalf("work not enriched: %+v", ub.Work)
	}
}

func TestUpsertPartialUpdatePreservesOtherFields(t *testing.T) {
	db := newTestDB(t)
	svc := library.NewService(db)
	wid := seedWork(t, db, "Dune", "Herbert")

	if _, err := svc.Upsert(context.Background(), 1, wid, library.UpsertRequest{
		Shelf: strptr("sci-fi"),
		Notes: strptr("re-read for book club"),
	}); err != nil {
		t.Fatalf("first upsert: %v", err)
	}
	// Second upsert touches only progress; shelf + notes must survive.
	ub, err := svc.Upsert(context.Background(), 1, wid, library.UpsertRequest{
		ProgressPercent: intptr(42),
	})
	if err != nil {
		t.Fatalf("second upsert: %v", err)
	}
	if ub.Shelf != "sci-fi" {
		t.Fatalf("shelf clobbered: %q", ub.Shelf)
	}
	if ub.Notes != "re-read for book club" {
		t.Fatalf("notes clobbered: %q", ub.Notes)
	}
	if ub.ProgressPercent != 42 {
		t.Fatalf("progress = %d, want 42", ub.ProgressPercent)
	}

	// And there's still exactly one row for (user, work).
	var n int64
	db.Model(&library.UserBook{}).Where("user_id = ? AND work_id = ?", 1, wid).Count(&n)
	if n != 1 {
		t.Fatalf("expected 1 row, got %d", n)
	}
}

func TestUpsertUnknownWorkReturnsErr(t *testing.T) {
	db := newTestDB(t)
	svc := library.NewService(db)
	_, err := svc.Upsert(context.Background(), 1, uuid.New(), library.UpsertRequest{})
	if !errors.Is(err, library.ErrWorkNotFound) {
		t.Fatalf("want ErrWorkNotFound, got %v", err)
	}
}

func TestUpsertInvalidInputs(t *testing.T) {
	db := newTestDB(t)
	svc := library.NewService(db)
	wid := seedWork(t, db, "Dune", "")

	cases := []struct {
		name string
		req  library.UpsertRequest
		want error
	}{
		{"bad status", library.UpsertRequest{Status: strptr("skimming")}, library.ErrInvalidStatus},
		{"rating too high", library.UpsertRequest{Rating: intptr(6)}, library.ErrInvalidRating},
		{"rating too low", library.UpsertRequest{Rating: intptr(0)}, library.ErrInvalidRating},
		{"percent over 100", library.UpsertRequest{ProgressPercent: intptr(101)}, library.ErrInvalidProgress},
		{"percent negative", library.UpsertRequest{ProgressPercent: intptr(-1)}, library.ErrInvalidProgress},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if _, err := svc.Upsert(context.Background(), 1, wid, c.req); !errors.Is(err, c.want) {
				t.Fatalf("want %v, got %v", c.want, err)
			}
		})
	}
}

func TestUpsertReadingStampsStartedAt(t *testing.T) {
	db := newTestDB(t)
	svc := library.NewService(db)
	wid := seedWork(t, db, "Dune", "")

	ub, err := svc.Upsert(context.Background(), 1, wid, library.UpsertRequest{Status: strptr("reading")})
	if err != nil {
		t.Fatalf("upsert: %v", err)
	}
	if ub.StartedAt == nil {
		t.Fatalf("StartedAt not stamped on ->reading")
	}
	if ub.FinishedAt != nil {
		t.Fatalf("FinishedAt should be nil while reading")
	}
}

func TestUpsertReadStampsFinishedAndForces100(t *testing.T) {
	db := newTestDB(t)
	svc := library.NewService(db)
	wid := seedWork(t, db, "Dune", "")

	ub, err := svc.Upsert(context.Background(), 1, wid, library.UpsertRequest{Status: strptr("read")})
	if err != nil {
		t.Fatalf("upsert: %v", err)
	}
	if ub.FinishedAt == nil || ub.StartedAt == nil {
		t.Fatalf("read should stamp both StartedAt and FinishedAt: %+v", ub)
	}
	if ub.ProgressPercent != 100 {
		t.Fatalf("read should force 100%%, got %d", ub.ProgressPercent)
	}
}

func TestUpsertReadWithExplicitPercentKeepsIt(t *testing.T) {
	db := newTestDB(t)
	svc := library.NewService(db)
	wid := seedWork(t, db, "Dune", "")

	ub, err := svc.Upsert(context.Background(), 1, wid, library.UpsertRequest{
		Status:          strptr("read"),
		ProgressPercent: intptr(90),
	})
	if err != nil {
		t.Fatalf("upsert: %v", err)
	}
	if ub.ProgressPercent != 90 {
		t.Fatalf("explicit percent overridden: got %d, want 90", ub.ProgressPercent)
	}
}

func TestListFiltersByStatusAndShelf(t *testing.T) {
	db := newTestDB(t)
	svc := library.NewService(db)
	w1 := seedWork(t, db, "A", "")
	w2 := seedWork(t, db, "B", "")
	w3 := seedWork(t, db, "C", "")

	mustUpsert(t, svc, 1, w1, library.UpsertRequest{Status: strptr("reading"), Shelf: strptr("sci-fi")})
	mustUpsert(t, svc, 1, w2, library.UpsertRequest{Status: strptr("read"), Shelf: strptr("sci-fi")})
	mustUpsert(t, svc, 1, w3, library.UpsertRequest{Status: strptr("reading"), Shelf: strptr("history")})
	// A different user's entry must never leak in.
	mustUpsert(t, svc, 2, w1, library.UpsertRequest{Status: strptr("reading")})

	reading, err := svc.List(context.Background(), library.ListFilter{UserID: 1, Status: library.StatusReading})
	if err != nil {
		t.Fatalf("list reading: %v", err)
	}
	if len(reading) != 2 {
		t.Fatalf("reading count = %d, want 2", len(reading))
	}

	sci, err := svc.List(context.Background(), library.ListFilter{UserID: 1, Shelf: "sci-fi"})
	if err != nil {
		t.Fatalf("list shelf: %v", err)
	}
	if len(sci) != 2 {
		t.Fatalf("sci-fi count = %d, want 2", len(sci))
	}

	all, _ := svc.List(context.Background(), library.ListFilter{UserID: 1})
	if len(all) != 3 {
		t.Fatalf("user 1 total = %d, want 3", len(all))
	}
}

func TestGetNotFound(t *testing.T) {
	db := newTestDB(t)
	svc := library.NewService(db)
	wid := seedWork(t, db, "A", "")
	if _, err := svc.Get(context.Background(), 1, wid); !errors.Is(err, library.ErrNotFound) {
		t.Fatalf("want ErrNotFound, got %v", err)
	}
}

func TestDeleteAndDeleteMissing(t *testing.T) {
	db := newTestDB(t)
	svc := library.NewService(db)
	wid := seedWork(t, db, "A", "")
	mustUpsert(t, svc, 1, wid, library.UpsertRequest{Status: strptr("want")})

	if err := svc.Delete(context.Background(), 1, wid); err != nil {
		t.Fatalf("delete: %v", err)
	}
	if _, err := svc.Get(context.Background(), 1, wid); !errors.Is(err, library.ErrNotFound) {
		t.Fatalf("entry still present after delete: %v", err)
	}
	if err := svc.Delete(context.Background(), 1, wid); !errors.Is(err, library.ErrNotFound) {
		t.Fatalf("delete-missing want ErrNotFound, got %v", err)
	}
}

func mustUpsert(t *testing.T, svc *library.Service, uid uint, wid uuid.UUID, req library.UpsertRequest) {
	t.Helper()
	if _, err := svc.Upsert(context.Background(), uid, wid, req); err != nil {
		t.Fatalf("upsert: %v", err)
	}
}
