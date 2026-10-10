//go:build postgres

package catalog_test

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/kararnab/libraryZ/internal/catalog"
	"github.com/kararnab/libraryZ/internal/storage"
)

// Parallel uploads by one user are counted one after the other (the user's
// row is locked), so exactly the allowance gets in.
func TestPostgresUploadQuotaConcurrent(t *testing.T) {
	db := openPostgres(t)
	store, err := storage.NewLocal(filepath.Join(t.TempDir(), "blobs"))
	if err != nil {
		t.Fatal(err)
	}
	svc := catalog.NewService(db, store)
	svc.SetUploadQuota(testQuota) // standard tier: 3 files
	old := time.Now().Add(-30 * 24 * time.Hour)
	if err := db.Exec(`INSERT INTO users (id, email, name, disabled, created_at, email_verified_at) VALUES (1, 'u@x', 'U', false, ?, ?)`,
		old, old).Error; err != nil {
		t.Fatal(err)
	}
	w := catalog.Work{Title: "Q"}
	if err := svc.CreateWork(context.Background(), &w); err != nil {
		t.Fatal(err)
	}

	const n = 10
	errs := make([]error, n)
	var wg sync.WaitGroup
	for i := range n {
		wg.Go(func() {
			_, errs[i] = svc.AddEdition(context.Background(), w.ID, "txt", "en", 1, "",
				bytes.NewReader([]byte(fmt.Sprintf("parallel %d", i))))
		})
	}
	wg.Wait()

	ok, limited := 0, 0
	for _, err := range errs {
		var q *catalog.QuotaExceededError
		switch {
		case err == nil:
			ok++
		case errors.As(err, &q):
			limited++
		default:
			t.Fatalf("unexpected error: %v", err)
		}
	}
	if ok != 3 || limited != n-3 {
		t.Fatalf("ok=%d limited=%d, want 3 and %d", ok, limited, n-3)
	}
}
