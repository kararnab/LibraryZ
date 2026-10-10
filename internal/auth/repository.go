package auth

import (
	"time"

	"github.com/google/uuid"
)

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
	// TokenVersion is embedded in every access token and checked by the auth
	// middleware; incrementing it revokes all of the user's access tokens.
	TokenVersion int `gorm:"not null;default:0" json:"-"`
}

// RefreshToken is one issued refresh token. Only a SHA-256 hash of the
// opaque token is stored. Tokens rotate on every use: the presented token is
// marked used and a new one in the same family is issued. Presenting an
// already-used token means it was stolen (or replayed), so the whole family
// is revoked.
type RefreshToken struct {
	ID        uuid.UUID `gorm:"type:uuid;primaryKey"`
	UserID    uint      `gorm:"not null;index"`
	FamilyID  uuid.UUID `gorm:"type:uuid;not null;index"`
	TokenHash string    `gorm:"size:64;not null;uniqueIndex"`
	ExpiresAt time.Time `gorm:"not null"`
	UsedAt    *time.Time
	RevokedAt *time.Time
	CreatedAt time.Time
}
