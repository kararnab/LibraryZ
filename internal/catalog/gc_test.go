package catalog_test

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/glebarez/sqlite"
	"github.com/google/uuid"
	"github.com/kararnab/libraryZ/internal/catalog"
	"github.com/kararnab/libraryZ/internal/migrations"
	"github.com/kararnab/libraryZ/internal/storage"
	"gorm.io/gorm"
)

func TestCollectGarbage(t *testing.T) {
	ctx := context.Background()
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	if err != nil {
		t.Fatal(err)
	}
	sqlDB, _ := db.DB()
	sqlDB.SetMaxOpenConns(1)
	if err := migrations.Up(ctx, db); err != nil {
		t.Fatal(err)
	}
	root := t.TempDir()
	store, _ := storage.NewLocal(root)
	svc := catalog.NewService(db, store)

	work := catalog.Work{Title: "W"}
	if err := svc.CreateWork(ctx, &work); err != nil {
		t.Fatal(err)
	}
	put := func(content string, age time.Duration) string {
		obj, err := store.Put(ctx, bytes.NewReader([]byte(content)))
		if err != nil {
			t.Fatal(err)
		}
		old := time.Now().Add(-age)
		if err := os.Chtimes(filepath.Join(root, obj.Key[:2], obj.Key), old, old); err != nil {
			t.Fatal(err)
		}
		return obj.Key
	}
	edition := func(key string, removedAgo time.Duration) {
		ed := catalog.Edition{ID: uuid.New(), WorkID: work.ID, Format: "txt", FileKey: key, SHA256: key}
		if removedAgo > 0 {
			ed.DeletedAt = gorm.DeletedAt{Time: time.Now().Add(-removedAgo), Valid: true}
		}
		if err := db.Create(&ed).Error; err != nil {
			t.Fatal(err)
		}
	}

	const day = 24 * time.Hour
	live := put("live", 90*day)
	edition(live, 0)
	orphan := put("orphan", 2*time.Hour)
	freshOrphan := put("fresh orphan", time.Minute) // may be mid-upload
	longRemoved := put("long removed", 90*day)
	edition(longRemoved, 40*day)
	recentlyRemoved := put("recently removed", 90*day)
	edition(recentlyRemoved, day)

	stats, err := svc.CollectGarbage(ctx, 30*day)
	if err != nil {
		t.Fatal(err)
	}
	if stats.Scanned != 5 || stats.Deleted != 2 {
		t.Fatalf("stats = %+v, want 5 scanned / 2 deleted", stats)
	}
	for key, want := range map[string]bool{
		live: true, orphan: false, freshOrphan: true, longRemoved: false, recentlyRemoved: true,
	} {
		if got, _ := store.Exists(ctx, key); got != want {
			t.Errorf("%s: exists = %v, want %v", key[:8], got, want)
		}
	}
}
