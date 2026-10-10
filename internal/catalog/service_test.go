package catalog_test

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"testing"

	"github.com/glebarez/sqlite"
	"github.com/google/uuid"
	"github.com/kararnab/libraryZ/internal/catalog"
	"github.com/kararnab/libraryZ/internal/migrations"
	"github.com/kararnab/libraryZ/internal/storage"
	"gorm.io/gorm"
)

type fixture struct {
	db    *gorm.DB
	root  string
	store *storage.Local
	svc   *catalog.Service
}

// newFixture uses a file-backed sqlite DB (not :memory:) so the
// concurrency test can use several connections against one database.
func newFixture(t *testing.T) fixture {
	t.Helper()
	dir := t.TempDir()
	db, err := gorm.Open(sqlite.Open(filepath.Join(dir, "test.db")+"?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)"), &gorm.Config{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if sqlDB, err := db.DB(); err == nil {
			sqlDB.Close()
		}
	})
	if err := migrations.Up(context.Background(), db); err != nil {
		t.Fatal(err)
	}
	root := filepath.Join(dir, "blobs")
	store, err := storage.NewLocal(root)
	if err != nil {
		t.Fatal(err)
	}
	return fixture{db: db, root: root, store: store, svc: catalog.NewService(db, store)}
}

func (f fixture) work(t *testing.T, title string) uuid.UUID {
	t.Helper()
	w := catalog.Work{Title: title}
	if err := f.svc.CreateWork(context.Background(), &w); err != nil {
		t.Fatal(err)
	}
	return w.ID
}

func (f fixture) add(workID uuid.UUID, sourceSHA, content string) (*catalog.Edition, error) {
	return f.svc.AddEdition(context.Background(), workID, "txt", "en", 1, sourceSHA, bytes.NewReader([]byte(content)))
}

func TestCreateWorkOwnsIDAndAssociations(t *testing.T) {
	f := newFixture(t)
	forced := uuid.New()
	w := catalog.Work{
		ID:       forced,
		Title:    "Moby-Dick",
		Editions: []catalog.Edition{{ID: uuid.New(), Format: "txt", FileKey: "x", SHA256: "y"}},
		Tags:     []catalog.Tag{{ID: uuid.New(), Name: "whales"}},
	}
	if err := f.svc.CreateWork(context.Background(), &w); err != nil {
		t.Fatal(err)
	}
	if w.ID == forced || w.ID == uuid.Nil {
		t.Fatalf("id should be server-generated, got %s", w.ID)
	}
	for _, tbl := range []string{"editions", "tags", "work_tags"} {
		var n int64
		f.db.Table(tbl).Count(&n)
		if n != 0 {
			t.Errorf("CreateWork wrote %d %s rows", n, tbl)
		}
	}
}

func TestAddEditionDuplicates(t *testing.T) {
	f := newFixture(t)
	a, b := f.work(t, "A"), f.work(t, "B")

	first, err := f.add(a, "src-1", "same bytes")
	if err != nil {
		t.Fatal(err)
	}

	var dup *catalog.DuplicateEditionError
	// Same stored bytes on another work → duplicate naming the original.
	if _, err := f.add(b, "src-2", "same bytes"); !errors.As(err, &dup) || dup.Existing.ID != first.ID || dup.Existing.WorkID != a {
		t.Fatalf("cross-work duplicate: err=%v", err)
	}
	// Same uploaded bytes (source hash) even if the stored bytes differ — the
	// PDF case, where sanitizing re-serializes.
	if _, err := f.add(a, "src-1", "different stored bytes"); !errors.As(err, &dup) || dup.Existing.ID != first.ID {
		t.Fatalf("source-hash duplicate: err=%v", err)
	}
	// Removed edition → ErrRemoved, not a duplicate to link to.
	if err := f.svc.DeleteEdition(context.Background(), first.ID, 1, "takedown"); err != nil {
		t.Fatal(err)
	}
	if _, err := f.add(b, "src-3", "same bytes"); !errors.Is(err, catalog.ErrRemoved) {
		t.Fatalf("re-upload of removed bytes: err=%v, want ErrRemoved", err)
	}
	if _, err := f.add(uuid.New(), "", "anything"); !errors.Is(err, catalog.ErrNotFound) {
		t.Fatalf("unknown work: err=%v", err)
	}
}

// Identical concurrent uploads: exactly one edition is recorded and every
// loser gets a DuplicateEditionError — including those that pass the lookup
// and lose on the unique index.
func TestAddEditionConcurrentDuplicates(t *testing.T) {
	f := newFixture(t)
	w := f.work(t, "Race")

	const n = 8
	var wg sync.WaitGroup
	errs := make([]error, n)
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			_, errs[i] = f.add(w, "race-src", "racing bytes")
		}(i)
	}
	wg.Wait()

	wins := 0
	for i, err := range errs {
		var dup *catalog.DuplicateEditionError
		switch {
		case err == nil:
			wins++
		case errors.As(err, &dup):
		default:
			t.Fatalf("worker %d: unexpected error %v", i, err)
		}
	}
	var rows int64
	f.db.Model(&catalog.Edition{}).Count(&rows)
	if wins != 1 || rows != 1 {
		t.Fatalf("wins=%d rows=%d, want 1/1", wins, rows)
	}
}

func TestListWorksPaging(t *testing.T) {
	f := newFixture(t)
	for i := 0; i < 5; i++ {
		f.work(t, fmt.Sprintf("W%d", i))
	}
	ctx := context.Background()
	page, err := f.svc.ListWorks(ctx, 2, 0)
	if err != nil || len(page) != 2 {
		t.Fatalf("page 1: %d %v", len(page), err)
	}
	rest, _ := f.svc.ListWorks(ctx, 10, 2)
	if len(rest) != 3 {
		t.Fatalf("offset 2: got %d, want 3", len(rest))
	}
	seen := map[uuid.UUID]bool{}
	for _, w := range append(page, rest...) {
		if seen[w.ID] {
			t.Fatalf("work %s returned twice across pages", w.Title)
		}
		seen[w.ID] = true
	}
	if past, _ := f.svc.ListWorks(ctx, 10, 50); len(past) != 0 {
		t.Fatalf("offset past the end: got %d", len(past))
	}
}

func TestListWorksHandlerClampsLimit(t *testing.T) {
	f := newFixture(t)
	for i := 0; i < 55; i++ {
		f.work(t, fmt.Sprintf("W%02d", i))
	}
	h := catalog.NewHandler(f.svc, 1<<20)
	for query, want := range map[string]int{
		"":                    50, // default
		"?limit=10":           10,
		"?limit=0":            50,
		"?limit=-5":           50,
		"?limit=1000":         50, // over the 200 cap → default
		"?limit=abc":          50,
		"?limit=10&offset=50": 5,
		"?offset=-3":          50, // negative offset ignored
	} {
		rec := httptest.NewRecorder()
		h.ListWorks(rec, httptest.NewRequest(http.MethodGet, "/works"+query, nil))
		var works []catalog.Work
		if err := json.NewDecoder(rec.Body).Decode(&works); err != nil {
			t.Fatalf("%q: %v", query, err)
		}
		if len(works) != want {
			t.Errorf("GET /works%s: %d works, want %d", query, len(works), want)
		}
	}
}

// If the stored object drifts from the recorded size, OpenEdition reports the
// size the backend actually has, so the handler's Content-Length is honest.
func TestOpenEditionReportsStorageSizeOnDrift(t *testing.T) {
	f := newFixture(t)
	w := f.work(t, "Drift")
	ed, err := f.add(w, "", "twelve bytes")
	if err != nil {
		t.Fatal(err)
	}
	f.db.Model(&catalog.Edition{}).Where("id = ?", ed.ID).Update("size_bytes", 999)

	dl, err := f.svc.OpenEdition(context.Background(), ed.ID)
	if err != nil {
		t.Fatal(err)
	}
	defer dl.Body.Close()
	got, _ := io.ReadAll(dl.Body)
	if dl.Size != int64(len(got)) || dl.Size != 12 || dl.Edition.SizeBytes != 999 {
		t.Fatalf("size=%d read=%d recorded=%d", dl.Size, len(got), dl.Edition.SizeBytes)
	}
	if dl.WorkTitle != "Drift" {
		t.Fatalf("work title = %q", dl.WorkTitle)
	}

	// A missing blob surfaces as an error, not a panic or empty stream.
	if err := os.Remove(filepath.Join(f.root, ed.FileKey[:2], ed.FileKey)); err != nil {
		t.Fatal(err)
	}
	if _, err := f.svc.OpenEdition(context.Background(), ed.ID); err == nil {
		t.Fatal("missing blob: want an error")
	}
}
