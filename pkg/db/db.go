package db

import (
	"github.com/kararnab/libraryZ/pkg/utils"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	"time"
)

// PoolConfig sizes the database/sql connection pool. MaxOpenConns must be
// chosen so instances × MaxOpenConns stays under Postgres max_connections
// (default 100) — see PLAN.md Phase 6.1.
type PoolConfig struct {
	MaxOpenConns    int
	MaxIdleConns    int
	ConnMaxLifetime time.Duration
}

func InitDB(databaseURL string, pool PoolConfig) (*gorm.DB, error) {
	// databaseURL = "host=localhost user=youruser password=yourpassword dbname=yourdb port=5432 sslmode=disable TimeZone=Asia/Shanghai"
	//
	// PrepareStmt is deliberately not enabled. pgbouncer (PLAN.md Slice 6.3)
	// runs in transaction-pooling mode, which rotates the server connection
	// per-transaction; an app-side statement cache breaks with "prepared
	// statement does not exist" the moment a cached name lands on a fresh
	// server conn. The connection string is expected to also carry
	// `default_query_exec_mode=exec` so pgx's own per-conn statement cache is
	// off for the same reason — see docker-compose.yml.
	db, err := gorm.Open(postgres.Open(databaseURL), &gorm.Config{})
	if err != nil {
		return nil, utils.WrappedError(utils.ErrCodeDBInitFailed, "failed to initialize database", err)
	}
	sqlDB, err := db.DB()
	if err != nil {
		return nil, utils.WrappedError(utils.ErrCodeDBSQLRetrievalFailed, "failed to get SQL DB from GORM DB", err)
	}

	// Idle conns above the open cap are meaningless — clamp so a mis-set env
	// can't ask for more idle than open.
	idle := pool.MaxIdleConns
	if idle > pool.MaxOpenConns {
		idle = pool.MaxOpenConns
	}
	sqlDB.SetMaxOpenConns(pool.MaxOpenConns)
	sqlDB.SetMaxIdleConns(idle)
	sqlDB.SetConnMaxLifetime(pool.ConnMaxLifetime)

	return db, nil
}
