package auth_test

import (
	"context"
	"fmt"
	"sync/atomic"
	"testing"
	"time"

	"github.com/glebarez/sqlite"
	"github.com/kararnab/iam/v2/onetime"
	"github.com/kararnab/iam/v2/session"
	"github.com/kararnab/iam/v2/storetest"
	"github.com/kararnab/libraryZ/internal/auth"
	"github.com/kararnab/libraryZ/internal/migrations"
	"gorm.io/gorm"
)

var dbSeq atomic.Int64

// freshDB is an empty, migrated in-memory sqlite database. One connection:
// every connection to a private ":memory:" DB would be a separate database,
// and it serializes writers the way storetest's concurrency checks expect.
func freshDB(t *testing.T) *gorm.DB {
	t.Helper()
	dsn := fmt.Sprintf("file:authstore%d?mode=memory&_pragma=foreign_keys(1)", dbSeq.Add(1))
	db, err := gorm.Open(sqlite.Open(dsn), &gorm.Config{})
	if err != nil {
		t.Fatal(err)
	}
	sqlDB, _ := db.DB()
	sqlDB.SetMaxOpenConns(1)
	t.Cleanup(func() { sqlDB.Close() })
	if err := migrations.Up(context.Background(), db); err != nil {
		t.Fatal(err)
	}
	return db
}

func TestUsersConformance(t *testing.T) {
	storetest.Users(t, func(t *testing.T) storetest.UserStore { return auth.NewUsers(freshDB(t)) })
}

func TestSessionsConformance(t *testing.T) {
	storetest.Sessions(t, func(t *testing.T) session.Store { return auth.NewSessions(freshDB(t)) })
}

func TestSessionsPurgerConformance(t *testing.T) {
	storetest.Purger(t, func(t *testing.T) storetest.PurgingSessionStore { return auth.NewSessions(freshDB(t)) })
}

func TestPurgeExpired(t *testing.T) {
	ctx := context.Background()
	s := auth.NewSessions(freshDB(t))
	now := time.Now().UTC().Truncate(time.Second)
	mk := func(id string, expires time.Time) []byte {
		_, hash := session.NewSecret()
		if err := s.Create(ctx, &session.Session{
			ID: id, SubjectID: "1", Mode: session.ModeBearer, TokenHash: hash,
			CreatedAt: now.Add(-time.Hour), LastUsedAt: now.Add(-time.Hour), ExpiresAt: expires,
		}); err != nil {
			t.Fatal(err)
		}
		return hash
	}
	oldHash := mk("expired", now.Add(-time.Minute))
	mk("live", now.Add(time.Hour))
	_, rotated := session.NewSecret()
	if err := s.Rotate(ctx, "expired", oldHash, rotated, now.Add(-30*time.Minute)); err != nil {
		t.Fatal(err)
	}

	n, err := s.PurgeExpired(ctx, now)
	if err != nil || n != 1 {
		t.Fatalf("PurgeExpired = %d, %v", n, err)
	}
	if _, err := s.Get(ctx, "expired"); err == nil {
		t.Fatal("expired session survived")
	}
	if _, _, err := s.GetByTokenHash(ctx, oldHash); err == nil {
		t.Fatal("rotated hash of a purged session survived")
	}
	if _, err := s.Get(ctx, "live"); err != nil {
		t.Fatalf("live session purged: %v", err)
	}
}

func TestTokensConformance(t *testing.T) {
	storetest.Tokens(t, func(t *testing.T) onetime.Store { return auth.NewTokens(freshDB(t)) })
}
