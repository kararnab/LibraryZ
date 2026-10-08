//go:build postgres

// Run with:
//
//	DATABASE_URL='postgres://user:pass@localhost:5432/libraryz?sslmode=disable' \
//	  go test -tags=postgres ./internal/migrations/...
//
// Drops and re-creates the public schema — do NOT point DATABASE_URL at a
// production-shaped DB.
package migrations_test

import (
	"context"
	"os"
	"sync"
	"testing"

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
	t.Cleanup(func() {
		if sqlDB, err := db.DB(); err == nil {
			sqlDB.Close()
		}
	})
	return db
}

// Several instances starting at once must not race each other's DDL: the
// advisory lock serializes them, the first applies, the rest see no-ops.
func TestPostgresConcurrentUp(t *testing.T) {
	admin := openPostgres(t)
	for _, s := range []string{`DROP SCHEMA public CASCADE`, `CREATE SCHEMA public`} {
		if err := admin.Exec(s).Error; err != nil {
			t.Fatalf("reset: %v", err)
		}
	}

	const n = 4
	var wg sync.WaitGroup
	errs := make([]error, n)
	for i := 0; i < n; i++ {
		db := openPostgres(t) // separate pool per "instance"
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			errs[i] = migrations.Up(context.Background(), db)
		}(i)
	}
	wg.Wait()
	for i, err := range errs {
		if err != nil {
			t.Fatalf("instance %d: %v", i, err)
		}
	}
	var dupes int64
	if err := admin.Raw(`SELECT COUNT(*) - COUNT(DISTINCT version_id) FROM goose_db_version`).Scan(&dupes).Error; err != nil {
		t.Fatal(err)
	}
	if dupes != 0 {
		t.Fatalf("%d migrations were applied more than once", dupes)
	}
}
