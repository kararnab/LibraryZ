//go:build postgres

// Postgres-only verification of the tsvector full-text search path. The
// default `go test ./...` skips this file entirely; run with:
//
//	DATABASE_URL='postgres://user:pass@localhost:5432/libraryz?sslmode=disable' \
//	  go test -tags=postgres ./internal/catalog/...
//
// The test drops and re-creates the public schema in the target
// database, so DO NOT point DATABASE_URL at a production-shaped DB.
package catalog_test

import (
	"context"
	"os"
	"testing"

	"github.com/google/uuid"
	"github.com/kararnab/libraryZ/internal/catalog"
	"github.com/kararnab/libraryZ/internal/migrations"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
)

func openPostgres(t *testing.T) *gorm.DB {
	t.Helper()
	dsn := os.Getenv("DATABASE_URL")
	if dsn == "" {
		t.Skip("DATABASE_URL not set; skipping postgres-tagged test")
	}
	db, err := gorm.Open(postgres.Open(dsn), &gorm.Config{})
	if err != nil {
		t.Fatalf("open postgres: %v", err)
	}
	// Hard reset: drop everything (including goose's version table) so the
	// migrations run from scratch each time.
	for _, s := range []string{`DROP SCHEMA public CASCADE`, `CREATE SCHEMA public`} {
		if err := db.Exec(s).Error; err != nil {
			t.Fatalf("reset (%s): %v", s, err)
		}
	}
	if err := migrations.Up(context.Background(), db); err != nil {
		t.Fatalf("migrate: %v", err)
	}
	return db
}

func TestPostgresSearchVectorMatchesTitle(t *testing.T) {
	db := openPostgres(t)
	svc := catalog.NewService(db, nil)
	ctx := context.Background()

	for _, w := range []catalog.Work{
		{ID: uuid.New(), Title: "Moby-Dick", Authors: "Herman Melville"},
		{ID: uuid.New(), Title: "Pride and Prejudice", Authors: "Jane Austen"},
	} {
		if err := svc.CreateWork(ctx, &w); err != nil {
			t.Fatalf("create %q: %v", w.Title, err)
		}
	}

	got, err := svc.SearchWorks(ctx, "moby", 50, 0)
	if err != nil {
		t.Fatalf("search: %v", err)
	}
	if len(got) != 1 || got[0].Title != "Moby-Dick" {
		t.Fatalf("title search: expected only Moby-Dick, got %+v", got)
	}
}

func TestPostgresSearchVectorMatchesAuthorAndDescription(t *testing.T) {
	db := openPostgres(t)
	svc := catalog.NewService(db, nil)
	ctx := context.Background()

	w1 := catalog.Work{
		ID: uuid.New(), Title: "Anonymous Title", Authors: "Jane Austen",
	}
	w2 := catalog.Work{
		ID:          uuid.New(),
		Title:       "Another Anonymous",
		Description: "A regency-era novel about manners.",
	}
	for _, w := range []catalog.Work{w1, w2} {
		ww := w
		if err := svc.CreateWork(ctx, &ww); err != nil {
			t.Fatal(err)
		}
	}

	gotAuthor, _ := svc.SearchWorks(ctx, "austen", 50, 0)
	if len(gotAuthor) != 1 || gotAuthor[0].Authors != "Jane Austen" {
		t.Fatalf("author search: %+v", gotAuthor)
	}

	gotDesc, _ := svc.SearchWorks(ctx, "regency", 50, 0)
	if len(gotDesc) != 1 || gotDesc[0].Description == "" {
		t.Fatalf("description search: %+v", gotDesc)
	}
}

func TestPostgresSearchVectorReflectsUpdates(t *testing.T) {
	db := openPostgres(t)
	svc := catalog.NewService(db, nil)
	ctx := context.Background()

	w := catalog.Work{ID: uuid.New(), Title: "Initial"}
	if err := svc.CreateWork(ctx, &w); err != nil {
		t.Fatal(err)
	}

	// Trigger should fire on UPDATE as well as INSERT.
	if err := db.Model(&catalog.Work{}).Where("id = ?", w.ID).
		Update("title", "Updated To Distinctive").Error; err != nil {
		t.Fatalf("update: %v", err)
	}

	got, err := svc.SearchWorks(ctx, "distinctive", 50, 0)
	if err != nil {
		t.Fatalf("search: %v", err)
	}
	if len(got) != 1 || got[0].Title != "Updated To Distinctive" {
		t.Fatalf("expected updated title to be searchable, got %+v", got)
	}
}

// A title hit must outrank a passing mention in a newer work's description,
// even though the description work was created later.
func TestPostgresSearchRanksTitleAboveDescription(t *testing.T) {
	db := openPostgres(t)
	svc := catalog.NewService(db, nil)
	ctx := context.Background()

	for _, w := range []catalog.Work{
		{ID: uuid.New(), Title: "Leviathan", Authors: "Thomas Hobbes"},
		{ID: uuid.New(), Title: "Whale Facts", Authors: "Leviathan Press"},
		{ID: uuid.New(), Title: "Sea Stories", Description: "Mentions a leviathan once."},
	} {
		ww := w
		if err := svc.CreateWork(ctx, &ww); err != nil {
			t.Fatal(err)
		}
	}

	got, err := svc.SearchWorks(ctx, "leviathan", 50, 0)
	if err != nil {
		t.Fatalf("search: %v", err)
	}
	if len(got) != 3 || got[0].Title != "Leviathan" || got[1].Title != "Whale Facts" || got[2].Title != "Sea Stories" {
		titles := make([]string, len(got))
		for i, w := range got {
			titles[i] = w.Title
		}
		t.Fatalf("ranking: want [Leviathan, Whale Facts, Sea Stories], got %v", titles)
	}
}

func TestPostgresSearchWebsearchSyntax(t *testing.T) {
	db := openPostgres(t)
	svc := catalog.NewService(db, nil)
	ctx := context.Background()

	for _, w := range []catalog.Work{
		{ID: uuid.New(), Title: "The Old Man and the Sea"},
		{ID: uuid.New(), Title: "The Sea Wolf"},
		{ID: uuid.New(), Title: "Old Sea Charts and Man"},
	} {
		ww := w
		if err := svc.CreateWork(ctx, &ww); err != nil {
			t.Fatal(err)
		}
	}

	phrase, _ := svc.SearchWorks(ctx, `"old man"`, 50, 0)
	if len(phrase) != 1 || phrase[0].Title != "The Old Man and the Sea" {
		t.Fatalf("phrase search: %+v", phrase)
	}
	excl, _ := svc.SearchWorks(ctx, "sea -wolf", 50, 0)
	for _, w := range excl {
		if w.Title == "The Sea Wolf" {
			t.Fatalf("exclusion ignored: %+v", excl)
		}
	}
	if len(excl) != 2 {
		t.Fatalf("exclusion search: want 2, got %+v", excl)
	}
}
