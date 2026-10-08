//go:build postgres

// Run with:
//
//	DATABASE_URL='postgres://user:pass@localhost:5432/libraryz?sslmode=disable' \
//	  go test -tags=postgres ./internal/runlock/...
package runlock_test

import (
	"context"
	"errors"
	"os"
	"testing"

	"github.com/kararnab/libraryZ/internal/runlock"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
)

func open(t *testing.T) *gorm.DB {
	t.Helper()
	dsn := os.Getenv("DATABASE_URL")
	if dsn == "" {
		t.Skip("DATABASE_URL not set; skipping postgres-tagged test")
	}
	db, err := gorm.Open(postgres.Open(dsn), &gorm.Config{})
	if err != nil {
		t.Fatal(err)
	}
	return db
}

func TestPostgresExclusiveSkipsWhileHeld(t *testing.T) {
	a, b := open(t), open(t) // two "instances"
	ctx := context.Background()

	inner := errors.New("unset")
	err := runlock.Exclusive(ctx, a, runlock.KeyRecTrainer, func(*gorm.DB) error {
		inner = runlock.Exclusive(ctx, b, runlock.KeyRecTrainer, func(*gorm.DB) error { return nil })
		// A different key is not blocked.
		if err := runlock.Exclusive(ctx, b, runlock.KeyBlobGC, func(*gorm.DB) error { return nil }); err != nil {
			t.Errorf("other key: %v", err)
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if !errors.Is(inner, runlock.ErrBusy) {
		t.Fatalf("second instance: err = %v, want ErrBusy", inner)
	}
	// Released at commit.
	if err := runlock.Exclusive(ctx, b, runlock.KeyRecTrainer, func(*gorm.DB) error { return nil }); err != nil {
		t.Fatalf("after release: %v", err)
	}
}
