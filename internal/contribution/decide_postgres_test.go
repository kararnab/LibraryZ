//go:build postgres

// Postgres-only concurrency check for the approve/reject state transition.
// sqlite serializes writers, so the race this guards against can't be
// reproduced there. Run with:
//
//	DATABASE_URL='postgres://user:pass@localhost:5432/libraryz?sslmode=disable' \
//	  go test -tags=postgres ./internal/contribution/...
//
// Drops and re-creates the catalog + contributions tables — do NOT point
// DATABASE_URL at a production-shaped DB.
package contribution_test

import (
	"context"
	"errors"
	"os"
	"sync"
	"testing"

	"github.com/kararnab/libraryZ/internal/catalog"
	"github.com/kararnab/libraryZ/internal/contribution"
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
	for _, s := range []string{
		`DROP TRIGGER IF EXISTS works_search_vector_trigger ON works`,
		`DROP FUNCTION IF EXISTS works_search_vector_update()`,
		`DROP TABLE IF EXISTS contributions, work_tags, editions, tags, works CASCADE`,
	} {
		if err := db.Exec(s).Error; err != nil {
			t.Fatalf("reset (%s): %v", s, err)
		}
	}
	if err := catalog.Migrate(db); err != nil {
		t.Fatalf("catalog migrate: %v", err)
	}
	if err := contribution.Migrate(db); err != nil {
		t.Fatalf("contribution migrate: %v", err)
	}
	return db
}

// Many moderators hit approve/reject on the same pending contribution at
// once: exactly one must win, and every loser must see ErrAlreadyDecided.
func TestPostgresConcurrentDecideHasSingleWinner(t *testing.T) {
	db := openPostgres(t)
	svc := contribution.NewService(db)
	ctx := context.Background()

	for round := 0; round < 20; round++ {
		workID := seedWork(t, db, "Original", "")
		c, err := svc.Submit(ctx, workID, 1, map[string]any{"title": "Edited"})
		if err != nil {
			t.Fatalf("submit: %v", err)
		}

		const n = 8
		var wg sync.WaitGroup
		errs := make([]error, n)
		start := make(chan struct{})
		for i := 0; i < n; i++ {
			wg.Add(1)
			go func(i int) {
				defer wg.Done()
				<-start
				if i%2 == 0 {
					_, errs[i] = svc.Approve(ctx, c.ID, uint(100+i))
				} else {
					_, errs[i] = svc.Reject(ctx, c.ID, uint(100+i))
				}
			}(i)
		}
		close(start)
		wg.Wait()

		wins := 0
		for i, err := range errs {
			switch {
			case err == nil:
				wins++
			case errors.Is(err, contribution.ErrAlreadyDecided):
			default:
				t.Fatalf("round %d worker %d: unexpected error %v", round, i, err)
			}
		}
		if wins != 1 {
			t.Fatalf("round %d: %d winners, want exactly 1", round, wins)
		}

		got, err := svc.Get(ctx, c.ID)
		if err != nil {
			t.Fatalf("get: %v", err)
		}
		var title string
		db.Table("works").Select("title").Where("id = ?", workID).Scan(&title)
		switch got.Status {
		case contribution.StatusApproved:
			if title != "Edited" {
				t.Fatalf("round %d: approved but title = %q", round, title)
			}
		case contribution.StatusRejected:
			if title != "Original" {
				t.Fatalf("round %d: rejected but title = %q", round, title)
			}
		default:
			t.Fatalf("round %d: status = %q", round, got.Status)
		}
	}
}
