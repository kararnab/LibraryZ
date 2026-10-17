//go:build postgres

// Run with:
//
//	DATABASE_URL='postgres://user:pass@localhost:5432/libraryz?sslmode=disable' \
//	  go test -tags=postgres ./internal/telemetry/...
//
// DROPS AND RECREATES the public schema.
package telemetry_test

import (
	"context"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/kararnab/libraryZ/internal/migrations"
	"github.com/kararnab/libraryZ/internal/telemetry"
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

// Reports of one crash arriving at once on several instances all count:
// the group upsert increments in the database, not in Go.
func TestPostgresConcurrentCrashesCountOnce(t *testing.T) {
	db := openPostgres(t)
	sink := telemetry.StoreSink{DB: db}
	const n = 20
	now := time.Now().UTC()
	var wg sync.WaitGroup
	for range n {
		wg.Add(1)
		go func() {
			defer wg.Done()
			err := sink.WriteCrashes(context.Background(), []telemetry.Crash{{
				Client:      telemetry.Client{Platform: "android", AppVersion: "1.0"},
				Kind:        "fatal",
				Exception:   "E",
				Stack:       []string{"at com.libraryz.X.y(X.kt:1)"},
				Fingerprint: "f00d",
				OccurredAt:  now,
				ReceivedAt:  now,
			}})
			if err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	var g telemetry.CrashGroup
	if err := db.First(&g, "fingerprint = ?", "f00d").Error; err != nil {
		t.Fatal(err)
	}
	if g.Count != n {
		t.Errorf("count %d, want %d", g.Count, n)
	}
}
