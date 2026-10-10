package catalog

import (
	"context"
	"time"

	"github.com/kararnab/libraryZ/internal/runlock"
	"github.com/kararnab/libraryZ/internal/storage"
	"gorm.io/gorm"
)

// gcUploadGrace protects blobs that were just written: AddEdition stores the
// blob before inserting the edition row, so a fresh blob with no row may
// simply be mid-upload.
const gcUploadGrace = time.Hour

// gcBatch is how many storage keys are checked per DB round-trip.
const gcBatch = 500

// GCStats summarizes one CollectGarbage pass.
type GCStats struct {
	Scanned int
	Deleted int
}

// CollectGarbage deletes stored blobs that no live edition references:
//
//   - orphans with no edition row at all (e.g. an upload whose DB insert
//     failed, or a re-upload of a removed file), and
//   - blobs whose editions were all removed by a moderator more than
//     `retention` ago. Within the retention window a removal can still be
//     reverted with the file intact.
//
// Blobs younger than gcUploadGrace are never touched. Storage is
// content-addressed, so a key is safe to delete only when *no* edition row
// shares it — that is what each batch checks. Runs on one instance at a time
// (runlock.KeyBlobGC); returns runlock.ErrBusy if another is already
// sweeping.
func (s *Service) CollectGarbage(ctx context.Context, retention time.Duration) (GCStats, error) {
	var stats GCStats
	err := runlock.Exclusive(ctx, s.db, runlock.KeyBlobGC, func(tx *gorm.DB) error {
		now := time.Now()
		var batch []string
		flush := func() error {
			n, err := s.sweep(ctx, tx, batch, now.Add(-retention))
			stats.Deleted += n
			batch = batch[:0]
			return err
		}
		err := s.store.List(ctx, func(o storage.ObjectInfo) error {
			stats.Scanned++
			if now.Sub(o.ModTime) < gcUploadGrace {
				return nil
			}
			batch = append(batch, o.Key)
			if len(batch) >= gcBatch {
				return flush()
			}
			return nil
		})
		if err != nil {
			return err
		}
		return flush()
	})
	return stats, err
}

// sweep deletes the keys in batch that are neither referenced by a live
// edition nor by one removed after `removedSince`.
func (s *Service) sweep(ctx context.Context, tx *gorm.DB, batch []string, removedSince time.Time) (int, error) {
	if len(batch) == 0 {
		return 0, nil
	}
	var keep []string
	if err := tx.Model(&Edition{}).Unscoped().
		Where("file_key IN ?", batch).
		Where("deleted_at IS NULL OR deleted_at > ?", removedSince).
		Distinct().Pluck("file_key", &keep).Error; err != nil {
		return 0, err
	}
	kept := make(map[string]struct{}, len(keep))
	for _, k := range keep {
		kept[k] = struct{}{}
	}
	deleted := 0
	for _, key := range batch {
		if _, ok := kept[key]; ok {
			continue
		}
		if err := s.store.Delete(ctx, key); err != nil {
			return deleted, err
		}
		deleted++
	}
	return deleted, nil
}
