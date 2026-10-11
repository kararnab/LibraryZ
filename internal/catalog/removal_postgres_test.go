//go:build postgres

package catalog_test

import (
	"path/filepath"
	"testing"
	"time"

	"github.com/kararnab/libraryZ/internal/catalog"
	"github.com/kararnab/libraryZ/internal/storage"
)

// The #27 scenario on Postgres, whose partial unique indexes on the edition
// hashes are what let a technically removed file come back.
func TestPostgresTechnicalRemovalReleasesTheFile(t *testing.T) {
	db := openPostgres(t)
	store, err := storage.NewLocal(filepath.Join(t.TempDir(), "blobs"))
	if err != nil {
		t.Fatal(err)
	}
	old := time.Now().Add(-30 * 24 * time.Hour)
	if err := db.Exec(`INSERT INTO users (id, email, name, disabled, created_at, email_verified_at) VALUES (1, 'm@x', 'M', false, ?, ?)`,
		old, old).Error; err != nil {
		t.Fatal(err)
	}
	if err := db.Exec(`INSERT INTO user_roles (user_id, role) VALUES (1, 'moderator')`).Error; err != nil {
		t.Fatal(err)
	}
	checkTechnicalRemoval(t, catalog.NewService(db, store))
}
