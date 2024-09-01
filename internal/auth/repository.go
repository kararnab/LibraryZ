package auth

import "gorm.io/gorm"

type User struct {
	ID       uint   `gorm:"primaryKey"`
	Email    string `gorm:"uniqueIndex"`
	Password string
	Name     string
}

func Migrate(db *gorm.DB) error {
	return db.AutoMigrate(&User{})
}
