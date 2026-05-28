package auth

import "gorm.io/gorm"

type User struct {
	ID       uint   `gorm:"primaryKey"`
	Email    string `gorm:"uniqueIndex"`
	Password string
	Name     string
	// IsModerator is `json:"-"` so a SignUp request body can't escalate
	// privileges. The Me handler builds its own response struct that
	// exposes this field on the way out. Promote users out-of-band via:
	//   UPDATE users SET is_moderator = true WHERE email = '...';
	IsModerator bool `json:"-"`
}

func Migrate(db *gorm.DB) error {
	return db.AutoMigrate(&User{})
}
