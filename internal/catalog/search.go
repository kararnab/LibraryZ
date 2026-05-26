package catalog

import "gorm.io/gorm"

// migratePostgresSearchExtras installs the Postgres-only full-text search
// machinery on top of the dialect-portable `works` table that AutoMigrate
// produces:
//
//   - `search_vector tsvector` column on works
//   - GIN index on search_vector
//   - BEFORE INSERT/UPDATE trigger that recomputes search_vector from
//     title + authors + description using the `simple` text-search
//     configuration (no stemming, no stopwords — keeps exact-word
//     matches behaving like users expect, e.g. "Brooks" stays "Brooks")
//   - one-time backfill so works inserted before the trigger existed
//     get a non-NULL search_vector
//
// Each statement is idempotent (`IF NOT EXISTS` / `CREATE OR REPLACE` /
// `DROP TRIGGER IF EXISTS`) so re-running Migrate on an already-migrated
// DB is a no-op.
//
// No-op on non-Postgres dialects — sqlite tests use the LOWER(LIKE)
// fallback in Service.SearchWorks.
func migratePostgresSearchExtras(db *gorm.DB) error {
	if db.Dialector.Name() != "postgres" {
		return nil
	}
	stmts := []string{
		`ALTER TABLE works ADD COLUMN IF NOT EXISTS search_vector tsvector`,
		`CREATE INDEX IF NOT EXISTS works_search_idx ON works USING gin(search_vector)`,
		`CREATE OR REPLACE FUNCTION works_search_vector_update() RETURNS trigger AS $$
			BEGIN
				NEW.search_vector := to_tsvector('simple',
					coalesce(NEW.title, '') || ' ' ||
					coalesce(NEW.authors, '') || ' ' ||
					coalesce(NEW.description, ''));
				RETURN NEW;
			END;
		$$ LANGUAGE plpgsql`,
		`DROP TRIGGER IF EXISTS works_search_vector_trigger ON works`,
		`CREATE TRIGGER works_search_vector_trigger
			BEFORE INSERT OR UPDATE ON works
			FOR EACH ROW EXECUTE FUNCTION works_search_vector_update()`,
		`UPDATE works SET search_vector = to_tsvector('simple',
			coalesce(title, '') || ' ' ||
			coalesce(authors, '') || ' ' ||
			coalesce(description, ''))
			WHERE search_vector IS NULL`,
	}
	for _, s := range stmts {
		if err := db.Exec(s).Error; err != nil {
			return err
		}
	}
	return nil
}
