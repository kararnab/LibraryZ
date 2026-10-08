package migrations_test

import (
	"context"
	"testing"

	"github.com/glebarez/sqlite"
	"github.com/kararnab/libraryZ/internal/auth"
	"github.com/kararnab/libraryZ/internal/catalog"
	"github.com/kararnab/libraryZ/internal/contribution"
	"github.com/kararnab/libraryZ/internal/library"
	"github.com/kararnab/libraryZ/internal/migrations"
	"github.com/kararnab/libraryZ/internal/recommendation"
	"gorm.io/gorm"
)

// liveModels are the structs the application actually reads and writes.
var liveModels = []any{
	&auth.User{},
	&catalog.Work{}, &catalog.Edition{}, &catalog.Tag{},
	&contribution.Contribution{},
	&library.UserBook{},
	&recommendation.WorkFactors{}, &recommendation.UserFactors{},
	&recommendation.WorkNeighbor{}, &recommendation.Dismissal{},
}

func openSQLite(t *testing.T) *gorm.DB {
	t.Helper()
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	if err != nil {
		t.Fatal(err)
	}
	// One connection: each new connection to ":memory:" is a separate DB.
	sqlDB, _ := db.DB()
	sqlDB.SetMaxOpenConns(1)
	return db
}

func TestUpIsIdempotent(t *testing.T) {
	db := openSQLite(t)
	for i := 0; i < 2; i++ {
		if err := migrations.Up(context.Background(), db); err != nil {
			t.Fatalf("up #%d: %v", i+1, err)
		}
	}
}

// Drift guard: every column a live model maps must exist after migrating.
// Fails when someone adds a field to a model without adding a migration.
func TestLiveModelsMatchMigratedSchema(t *testing.T) {
	db := openSQLite(t)
	if err := migrations.Up(context.Background(), db); err != nil {
		t.Fatal(err)
	}
	m := db.Migrator()
	for _, model := range liveModels {
		stmt := &gorm.Statement{DB: db}
		if err := stmt.Parse(model); err != nil {
			t.Fatal(err)
		}
		if !m.HasTable(model) {
			t.Errorf("table %s missing", stmt.Schema.Table)
			continue
		}
		for _, f := range stmt.Schema.Fields {
			if f.DBName == "" || f.IgnoreMigration {
				continue
			}
			if !m.HasColumn(model, f.DBName) {
				t.Errorf("%s.%s: column missing — add a migration", stmt.Schema.Table, f.DBName)
			}
		}
		for _, rel := range stmt.Schema.Relationships.Relations {
			if rel.JoinTable != nil && !m.HasTable(rel.JoinTable.Table) {
				t.Errorf("join table %s missing", rel.JoinTable.Table)
			}
		}
	}
}
