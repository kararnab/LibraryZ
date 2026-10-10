//go:build postgres

// Run with:
//
//	DATABASE_URL='postgres://user:pass@localhost:5432/libraryz?sslmode=disable' \
//	  go test -tags=postgres ./internal/recommendation/...
//
// Drops and re-creates the public schema — do NOT point DATABASE_URL at a
// production-shaped DB.
package recommendation_test

import (
	"context"
	"errors"
	"os"
	"testing"

	"github.com/kararnab/libraryZ/internal/migrations"
	"github.com/kararnab/libraryZ/internal/recommendation"
	"github.com/kararnab/libraryZ/internal/runlock"
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
	return db
}

// A second instance must skip — not queue behind, not race — while the
// first is training.
func TestPostgresTrainSkipsWhileAnotherInstanceTrains(t *testing.T) {
	db := openPostgres(t)
	for _, s := range []string{`DROP SCHEMA public CASCADE`, `CREATE SCHEMA public`} {
		if err := db.Exec(s).Error; err != nil {
			t.Fatalf("reset: %v", err)
		}
	}
	if err := migrations.Up(context.Background(), db); err != nil {
		t.Fatal(err)
	}
	other := openPostgres(t)
	ctx := context.Background()

	var inner error
	err := runlock.Exclusive(ctx, db, runlock.KeyRecTrainer, func(*gorm.DB) error {
		inner = recommendation.NewTrainer(other, recommendation.DefaultConfig()).Train(ctx)
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if !errors.Is(inner, runlock.ErrBusy) {
		t.Fatalf("concurrent train: err = %v, want runlock.ErrBusy", inner)
	}
	if err := recommendation.NewTrainer(other, recommendation.DefaultConfig()).Train(ctx); err != nil {
		t.Fatalf("train after release: %v", err)
	}
}
