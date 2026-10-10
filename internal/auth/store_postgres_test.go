//go:build postgres

// Run with:
//
//	DATABASE_URL='postgres://user:pass@localhost:5432/libraryz?sslmode=disable' \
//	  go test -tags=postgres ./internal/auth/...
//
// Drops and re-creates the public schema — do NOT point DATABASE_URL at a
// production-shaped DB.
package auth_test

import (
	"context"
	"os"
	"testing"

	"github.com/kararnab/iam/v2/session"
	"github.com/kararnab/iam/v2/storetest"
	"github.com/kararnab/libraryZ/internal/auth"
	"github.com/kararnab/libraryZ/internal/migrations"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
)

// freshPostgres resets the schema and migrates it, so each storetest
// factory call gets empty stores. Concurrent rotation here exercises real
// row locking, unlike sqlite's single writer.
func freshPostgres(t *testing.T) *gorm.DB {
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
	for _, s := range []string{`DROP SCHEMA public CASCADE`, `CREATE SCHEMA public`} {
		if err := db.Exec(s).Error; err != nil {
			t.Fatalf("reset: %v", err)
		}
	}
	if err := migrations.Up(context.Background(), db); err != nil {
		t.Fatal(err)
	}
	return db
}

func TestPostgresUsersConformance(t *testing.T) {
	storetest.Users(t, func(t *testing.T) storetest.UserStore { return auth.NewUsers(freshPostgres(t)) })
}

func TestPostgresSessionsConformance(t *testing.T) {
	storetest.Sessions(t, func(t *testing.T) session.Store { return auth.NewSessions(freshPostgres(t)) })
}
