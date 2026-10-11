//go:build postgres

// Postgres-only: concurrent offline syncs to one entry. Each writes the whole
// row, so without the row lock two devices changing different groups at once
// would lose one change. sqlite serializes writers and can't show it.
//
// Drops and re-creates the public schema — do NOT point DATABASE_URL at a
// production-shaped DB.
package library_test

import (
	"context"
	"os"
	"sync"
	"testing"

	"github.com/kararnab/libraryZ/internal/library"
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

func TestPostgresConcurrentGroupsAllLand(t *testing.T) {
	db := openPostgres(t)
	svc := library.NewService(db)
	ctx := context.Background()

	for round := 0; round < 20; round++ {
		wid := seedWork(t, db, "Dune", "Herbert")
		if _, err := svc.Upsert(ctx, 1, wid, library.UpsertRequest{}); err != nil {
			t.Fatal(err)
		}
		writes := []library.UpsertRequest{
			{CurrentPage: intptr(120), Stamps: library.Stamps{Progress: at(1, "a")}},
			{Rating: intptr(4), Stamps: library.Stamps{Rating: at(1, "b")}},
			{Shelf: strptr("Sci-fi"), Stamps: library.Stamps{Shelf: at(1, "c")}},
			{Notes: strptr("spice"), Stamps: library.Stamps{Notes: at(1, "d")}},
			{Status: strptr("reading"), Stamps: library.Stamps{Status: at(1, "e")}},
		}
		var wg sync.WaitGroup
		for _, w := range writes {
			wg.Add(1)
			go func(w library.UpsertRequest) {
				defer wg.Done()
				if _, err := svc.Upsert(ctx, 1, wid, w); err != nil {
					t.Error(err)
				}
			}(w)
		}
		wg.Wait()
		ub, err := svc.Get(ctx, 1, wid)
		if err != nil {
			t.Fatal(err)
		}
		if ub.CurrentPage != 120 || ub.Rating == nil || *ub.Rating != 4 || ub.Shelf != "Sci-fi" || ub.Notes != "spice" || ub.Status != library.StatusReading {
			t.Fatalf("round %d: a change was lost: %+v", round, ub)
		}
	}
}
