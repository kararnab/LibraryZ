// Package migrations owns the database schema. Migrations are versioned with
// goose and written as Go functions over GORM so the same code runs on
// Postgres (production) and sqlite (tests — keeps `go test` Docker-free).
//
// Rules:
//   - Never edit a migration that has shipped in a tagged release; add a new
//     one. (Before v0.1.0, 00001 is still the editable baseline.)
//   - Postgres-only DDL is dialect-gated inside the migration and no-ops on
//     sqlite (see postgresSearch).
//   - Up must run against a *direct* Postgres connection, not through
//     pgbouncer in transaction-pooling mode: it holds a session-level
//     advisory lock so concurrent instances don't race each other's DDL.
package migrations

import (
	"context"
	"database/sql"
	"fmt"

	"github.com/kararnab/libraryZ/internal/migrations/baseline"
	"github.com/pressly/goose/v3"
	"github.com/pressly/goose/v3/lock"
	"gorm.io/gorm"
)

// all returns the ordered migration list. Each step gets a GORM handle bound
// to goose's transaction, so DDL and version bookkeeping commit together
// (Postgres and sqlite both have transactional DDL).
func all(db *gorm.DB) []*goose.Migration {
	return []*goose.Migration{
		goose.NewGoMigration(1, onTx(db, migrateBaseline), nil),
	}
}

// Up applies all pending migrations. On Postgres it serializes concurrent
// callers with a session advisory lock (see package doc).
func Up(ctx context.Context, db *gorm.DB) error {
	sqlDB, err := db.DB()
	if err != nil {
		return err
	}
	var (
		dialect goose.Dialect
		opts    = []goose.ProviderOption{goose.WithGoMigrations(all(db)...), goose.WithDisableGlobalRegistry(true)}
	)
	switch name := db.Dialector.Name(); name {
	case "postgres":
		dialect = goose.DialectPostgres
		// Poll every second (goose's default is 5s) for up to 5 minutes, so
		// a waiting instance starts promptly once the winner finishes.
		locker, err := lock.NewPostgresSessionLocker(lock.WithLockTimeout(1, 300))
		if err != nil {
			return err
		}
		opts = append(opts, goose.WithSessionLocker(locker))
	case "sqlite":
		dialect = goose.DialectSQLite3
	default:
		return fmt.Errorf("migrations: unsupported dialect %q", name)
	}
	p, err := goose.NewProvider(dialect, sqlDB, nil, opts...)
	if err != nil {
		return err
	}
	_, err = p.Up(ctx)
	return err
}

// onTx adapts a GORM-based migration step to goose's transactional Go
// migration signature.
func onTx(db *gorm.DB, fn func(*gorm.DB) error) *goose.GoFunc {
	return &goose.GoFunc{RunTx: func(ctx context.Context, tx *sql.Tx) error {
		g := db.Session(&gorm.Session{NewDB: true, Context: ctx})
		g.Statement.ConnPool = tx
		return fn(g)
	}}
}

// 00001: the full schema as of the first release.
func migrateBaseline(tx *gorm.DB) error {
	if err := tx.AutoMigrate(baseline.All()...); err != nil {
		return err
	}
	return postgresSearch(tx)
}

// postgresSearch installs the Postgres-only full-text search machinery on top
// of the dialect-portable works table:
//
//   - `search_vector tsvector` column + GIN index
//   - BEFORE INSERT/UPDATE trigger recomputing search_vector from title +
//     authors + description with the `simple` configuration (no stemming,
//     no stopwords — "Brooks" stays "Brooks")
//
// No-op on sqlite — Service.SearchWorks falls back to LOWER(LIKE) there.
func postgresSearch(tx *gorm.DB) error {
	if tx.Dialector.Name() != "postgres" {
		return nil
	}
	stmts := []string{
		`ALTER TABLE works ADD COLUMN search_vector tsvector`,
		`CREATE INDEX works_search_idx ON works USING gin(search_vector)`,
		`CREATE FUNCTION works_search_vector_update() RETURNS trigger AS $$
			BEGIN
				NEW.search_vector := to_tsvector('simple',
					coalesce(NEW.title, '') || ' ' ||
					coalesce(NEW.authors, '') || ' ' ||
					coalesce(NEW.description, ''));
				RETURN NEW;
			END;
		$$ LANGUAGE plpgsql`,
		`CREATE TRIGGER works_search_vector_trigger
			BEFORE INSERT OR UPDATE ON works
			FOR EACH ROW EXECUTE FUNCTION works_search_vector_update()`,
	}
	for _, s := range stmts {
		if err := tx.Exec(s).Error; err != nil {
			return err
		}
	}
	return nil
}
