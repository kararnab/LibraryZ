package catalog

import "gorm.io/gorm"

type Book struct {
	ID       string `gorm:"primaryKey"`
	Title    string
	Author   string
	Genre    string
	Keywords []string
}

func Migrate(db *gorm.DB) error {
	return db.AutoMigrate(&Book{})
}
