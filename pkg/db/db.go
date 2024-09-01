package db

import (
	"github.com/kararnab/libraryZ/pkg/utils"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	"time"
)

func InitDB(databaseURL string) (*gorm.DB, error) {
	// databaseURL = "host=localhost user=youruser password=yourpassword dbname=yourdb port=5432 sslmode=disable TimeZone=Asia/Shanghai"
	db, err := gorm.Open(postgres.Open(databaseURL), &gorm.Config{
		PrepareStmt: true,
	})
	if err != nil {
		return nil, utils.WrappedError(utils.ErrCodeDBInitFailed, "failed to initialize database", err)
	}
	sqlDB, err := db.DB()
	if err != nil {
		return nil, utils.WrappedError(utils.ErrCodeDBSQLRetrievalFailed, "failed to get SQL DB from GORM DB", err)
	}

	// Set connection pool parameters
	sqlDB.SetMaxIdleConns(10)
	sqlDB.SetMaxOpenConns(100)
	sqlDB.SetConnMaxLifetime(time.Hour)

	return db, nil
}
