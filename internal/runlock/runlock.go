// Package runlock makes periodic background jobs (recommendation training,
// blob GC) run on exactly one instance at a time when several share a
// Postgres database.
package runlock

import (
	"context"
	"errors"

	"gorm.io/gorm"
)

// Lock keys for pg advisory locks. Arbitrary but fixed; distinct per job so
// unrelated jobs don't block each other.
const (
	KeyRecTrainer int64 = 0x4c425a_0001 // "LBZ" + 1
	KeyBlobGC     int64 = 0x4c425a_0002
)

// ErrBusy means another instance holds the lock; the caller should skip this
// run rather than wait.
var ErrBusy = errors.New("runlock: another instance is running this job")

// Exclusive runs fn inside a transaction that holds the cluster-wide lock
// for key, or returns ErrBusy without running fn if another instance holds
// it.
//
// On Postgres this is pg_try_advisory_xact_lock: the lock is tied to the
// transaction (released on commit/rollback), so it is safe through
// pgbouncer's transaction pooling — unlike a session-level advisory lock,
// which would be stranded on whatever server connection pgbouncer handed
// out. On sqlite (tests, single-process dev) there is only one process, so
// fn simply runs in a transaction.
func Exclusive(ctx context.Context, db *gorm.DB, key int64, fn func(tx *gorm.DB) error) error {
	return db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if tx.Dialector.Name() == "postgres" {
			var got bool
			if err := tx.Raw("SELECT pg_try_advisory_xact_lock(?)", key).Scan(&got).Error; err != nil {
				return err
			}
			if !got {
				return ErrBusy
			}
		}
		return fn(tx)
	})
}
