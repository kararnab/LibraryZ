package auth

import (
	"time"

	"gorm.io/datatypes"
)

// User is the application's subject: the row iam's Subject.ID points at
// (as a decimal string). It holds profile data only; how someone signs in
// lives in Identity + PasswordCredential, and what they may do in UserRole.
type User struct {
	ID    uint   `gorm:"primaryKey"`
	Email string `gorm:"uniqueIndex"`
	Name  string
	// Disabled users can't log in or refresh (iam rejects them).
	Disabled  bool `gorm:"not null;default:false"`
	CreatedAt time.Time
	// EmailVerifiedAt is set when the user follows an email-verification
	// link for their current address; nil means unverified.
	EmailVerifiedAt *time.Time
}

// UserRole grants a role (see Roles) to a user. There is no admin endpoint;
// promote out-of-band:
//
//	INSERT INTO user_roles (user_id, role)
//	SELECT id, 'moderator' FROM users WHERE email = 'you@example.com';
type UserRole struct {
	UserID uint   `gorm:"primaryKey;autoIncrement:false"`
	Role   string `gorm:"primaryKey;size:64"`
	User   User   `gorm:"foreignKey:UserID;constraint:OnDelete:CASCADE"`
}

// Identity links a provider identity ("password" + normalized email) to a
// user. One user may have several.
type Identity struct {
	Provider   string `gorm:"primaryKey;size:64"`
	ProviderID string `gorm:"primaryKey;size:320"`
	UserID     uint   `gorm:"not null;index"`
	User       User   `gorm:"foreignKey:UserID;constraint:OnDelete:CASCADE"`
	CreatedAt  time.Time
}

// PasswordCredential is a password hash by normalized login. It exists
// before its user does (iam registers the credential, then creates and
// links the subject), so it is keyed by login, not user id.
type PasswordCredential struct {
	Login     string `gorm:"primaryKey;size:320"`
	Hash      string `gorm:"not null"`
	UpdatedAt time.Time
}

// Session is an iam session (a refresh-token family in bearer mode). Only
// the SHA-256 of the current refresh token is stored.
type Session struct {
	ID         string         `gorm:"primaryKey;size:64"`
	SubjectID  string         `gorm:"not null;index;size:32"`
	Mode       string         `gorm:"not null;size:16"`
	TokenHash  []byte         `gorm:"not null;uniqueIndex"`
	CreatedAt  time.Time      `gorm:"not null"`
	LastUsedAt time.Time      `gorm:"not null"`
	ExpiresAt  time.Time      `gorm:"not null;index"`
	Attrs      datatypes.JSON // map[string]string: ip, user_agent, provider
}

// SessionRotation remembers a refresh token that was rotated away, so
// presenting it again is detected as reuse.
type SessionRotation struct {
	TokenHash []byte    `gorm:"primaryKey"`
	SessionID string    `gorm:"not null;index;size:64"`
	Session   Session   `gorm:"foreignKey:SessionID;constraint:OnDelete:CASCADE"`
	RotatedAt time.Time `gorm:"not null"`
}

// OneTimeToken backs iam's onetime.Store: single-use, expiring password
// reset and email verification tokens. Only the SHA-256 of the secret is
// stored. A new request deletes the subject's earlier tokens of the same
// purpose, so the table stays small; expired rows are purged on a ticker.
type OneTimeToken struct {
	ID        string    `gorm:"primaryKey;size:64"`
	TokenHash []byte    `gorm:"not null;uniqueIndex"`
	Purpose   string    `gorm:"not null;size:32;index:idx_one_time_tokens_subject_purpose,priority:2"`
	SubjectID string    `gorm:"not null;size:32;index:idx_one_time_tokens_subject_purpose,priority:1"`
	Login     string    `gorm:"size:320"` // password reset: the login
	Email     string    `gorm:"size:320"` // email verification: the address
	CreatedAt time.Time `gorm:"not null"`
	ExpiresAt time.Time `gorm:"not null;index"`
	UsedAt    *time.Time
}

// AccountEmail records each reset or verification email sent to an
// account, to cap them per account (a cooldown and a daily limit) so no one
// can flood a person's inbox by rotating IPs. Rows older than a day are
// purged on a ticker.
type AccountEmail struct {
	ID     uint      `gorm:"primaryKey"`
	UserID uint      `gorm:"not null;index:idx_account_emails_user_kind_sent,priority:1"`
	Kind   string    `gorm:"not null;size:32;index:idx_account_emails_user_kind_sent,priority:2"`
	SentAt time.Time `gorm:"not null;index:idx_account_emails_user_kind_sent,priority:3"`
}
