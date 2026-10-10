//go:build postgres

// Run with DATABASE_URL set (see internal/auth/store_postgres_test.go).
// Drops and re-creates the public schema.
package server_test

import (
	"context"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"testing"

	"github.com/kararnab/libraryZ/internal/auth"
	"github.com/kararnab/libraryZ/internal/migrations"
	"github.com/kararnab/libraryZ/internal/server"
	"github.com/kararnab/libraryZ/internal/storage"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
)

func newPostgresRecoveryServer(t *testing.T) (string, *outbox) {
	t.Helper()
	dsn := os.Getenv("DATABASE_URL")
	if dsn == "" {
		t.Skip("DATABASE_URL not set; skipping postgres-tagged test")
	}
	db, err := gorm.Open(postgres.Open(dsn), &gorm.Config{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if sqlDB, err := db.DB(); err == nil {
			sqlDB.Close()
		}
	})
	for _, s := range []string{`DROP SCHEMA public CASCADE`, `CREATE SCHEMA public`} {
		if err := db.Exec(s).Error; err != nil {
			t.Fatal(err)
		}
	}
	if err := migrations.Up(context.Background(), db); err != nil {
		t.Fatal(err)
	}
	store, err := storage.NewLocal(filepath.Join(t.TempDir(), "blobs"))
	if err != nil {
		t.Fatal(err)
	}
	box := &outbox{}
	deps := server.Deps{
		DB: db, Storage: store, MaxUploadBytes: 1 << 20, JWTSecret: testJWTSecret,
		LoadSubjectOnAccess: true,
		Mail:                auth.Mail{Outbox: box, PublicURL: recoveryPublicURL},
	}
	return newServer(t, deps).URL, box
}

// Concurrent requests for one account are counted one after the other
// (the user's row is locked), so exactly one email goes out.
func TestPostgresResetCapUnderConcurrency(t *testing.T) {
	base, box := newPostgresRecoveryServer(t)
	const email = "pg-cap@example.com"
	signupLogin(t, base, email)

	var wg sync.WaitGroup
	codes := make(chan int, 10)
	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			code, _ := post(t, base+"/auth/password-reset", "", map[string]string{"email": email})
			codes <- code
		}()
	}
	wg.Wait()
	close(codes)
	for c := range codes {
		if c != http.StatusAccepted && c != http.StatusTooManyRequests {
			t.Fatalf("unexpected status %d", c)
		}
	}
	msgs := box.sent(auth.KindPasswordReset, email)
	if len(msgs) != 1 {
		t.Fatalf("want exactly 1 reset email, got %d", len(msgs))
	}
	// And the link it carries works.
	tok := tokenIn(t, msgs[0], "reset-password")
	if code, body := post(t, base+"/auth/password-reset/complete", "", map[string]string{"token": tok, "new_password": "pg-new-passphrase"}); code != http.StatusNoContent {
		t.Fatalf("complete: %d %q", code, body)
	}
}
