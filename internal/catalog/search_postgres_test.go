//go:build postgres

// Postgres-only verification of the tsvector full-text search path. The
// default `go test ./...` skips this file entirely; run with:
//
//	DATABASE_URL='postgres://user:pass@localhost:5432/libraryz?sslmode=disable' \
//	  go test -tags=postgres ./internal/catalog/...
//
// The test wipes and re-creates the catalog tables in the target
// database, so DO NOT point DATABASE_URL at a production-shaped DB.
package catalog_test

import (
	"context"
	"os"
	"testing"

	"github.com/google/uuid"
	"github.com/kararnab/libraryZ/internal/catalog"
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
	// Hard reset the catalog tables. Trigger + function drop too so the
	// migrate path re-installs them fresh each run.
	stmts := []string{
		`DROP TRIGGER IF EXISTS works_search_vector_trigger ON works`,
		`DROP FUNCTION IF EXISTS works_search_vector_update()`,
		`DROP TABLE IF EXISTS work_tags, editions, tags, works CASCADE`,
	}
	for _, s := range stmts {
		if err := db.Exec(s).Error; err != nil {
			t.Fatalf("reset (%s): %v", s, err)
		}
	}
	if err := catalog.Migrate(db); err != nil {
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
