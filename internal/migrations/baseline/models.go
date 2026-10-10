// Package baseline is a frozen snapshot of the schema that migration 00001
// creates. These types deliberately duplicate the live models (catalog.Work,
// auth.User, ...) so later edits to those structs can never silently change
// what the baseline migration does. Type and field names match the live
// models exactly so GORM derives the same table, column, index and
// constraint names.
//
// Pre-v0.1.0 this snapshot may still be edited in place (there is no
// deployed data to preserve). Once v0.1.0 is tagged it is frozen: schema
// changes go in a new numbered migration instead.
package baseline

import (
	"time"

	"github.com/google/uuid"
	"gorm.io/datatypes"
	"gorm.io/gorm"
)

type User struct {
	ID        uint   `gorm:"primaryKey"`
	Email     string `gorm:"uniqueIndex"`
	Name      string
	Disabled  bool `gorm:"not null;default:false"`
	CreatedAt time.Time
	// EmailVerifiedAt is set when the user follows an email-verification
	// link for their current address; nil means unverified.
	EmailVerifiedAt *time.Time
}

type UserRole struct {
	UserID uint   `gorm:"primaryKey;autoIncrement:false"`
	Role   string `gorm:"primaryKey;size:64"`
	User   User   `gorm:"foreignKey:UserID;constraint:OnDelete:CASCADE"`
}

type Identity struct {
	Provider   string `gorm:"primaryKey;size:64"`
	ProviderID string `gorm:"primaryKey;size:320"`
	UserID     uint   `gorm:"not null;index"`
	User       User   `gorm:"foreignKey:UserID;constraint:OnDelete:CASCADE"`
	CreatedAt  time.Time
}

type PasswordCredential struct {
	Login     string `gorm:"primaryKey;size:320"`
	Hash      string `gorm:"not null"`
	UpdatedAt time.Time
}

type Session struct {
	ID         string    `gorm:"primaryKey;size:64"`
	SubjectID  string    `gorm:"not null;index;size:32"`
	Mode       string    `gorm:"not null;size:16"`
	TokenHash  []byte    `gorm:"not null;uniqueIndex"`
	CreatedAt  time.Time `gorm:"not null"`
	LastUsedAt time.Time `gorm:"not null"`
	ExpiresAt  time.Time `gorm:"not null;index"`
	Attrs      datatypes.JSON
}

type SessionRotation struct {
	TokenHash []byte    `gorm:"primaryKey"`
	SessionID string    `gorm:"not null;index;size:64"`
	Session   Session   `gorm:"foreignKey:SessionID;constraint:OnDelete:CASCADE"`
	RotatedAt time.Time `gorm:"not null"`
}

type Work struct {
	ID              uuid.UUID `gorm:"type:uuid;primaryKey"`
	Title           string    `gorm:"not null;index"`
	Subtitle        string
	Authors         string
	Description     string
	Language        string
	PublicationYear int
	ISBN            string `gorm:"index"`
	OpenLibraryID   string `gorm:"index"`
	CreatedAt       time.Time
	UpdatedAt       time.Time
	CreatedByUserID *uint          `gorm:"index"`
	DeletedAt       gorm.DeletedAt `gorm:"index"`
	DeletedBy       *uint
	DeleteReason    string

	Editions []Edition `gorm:"foreignKey:WorkID;constraint:OnDelete:CASCADE"`
	Tags     []Tag     `gorm:"many2many:work_tags;"`
}

type Edition struct {
	ID               uuid.UUID `gorm:"type:uuid;primaryKey"`
	WorkID           uuid.UUID `gorm:"type:uuid;not null;index"`
	Format           string    `gorm:"not null"`
	Language         string
	FileKey          string `gorm:"not null;index"`
	SizeBytes        int64
	SHA256           string  `gorm:"size:64;uniqueIndex"`
	SourceSHA256     *string `gorm:"size:64;uniqueIndex"`
	UploadedByUserID uint    `gorm:"not null;index"`
	CreatedAt        time.Time
	DeletedAt        gorm.DeletedAt `gorm:"index"`
	DeletedBy        *uint
	DeleteReason     string
}

type Tag struct {
	ID    uuid.UUID `gorm:"type:uuid;primaryKey"`
	Name  string    `gorm:"uniqueIndex;not null"`
	Works []Work    `gorm:"many2many:work_tags;"`
}

type Contribution struct {
	ID            uuid.UUID `gorm:"type:uuid;primaryKey"`
	WorkID        uuid.UUID `gorm:"type:uuid;not null;index"`
	ContributorID uint      `gorm:"not null;index"`
	Status        string    `gorm:"not null;index;default:pending"`
	Patch         datatypes.JSON
	ReviewerID    *uint `gorm:"index"`
	DecidedAt     *time.Time
	CreatedAt     time.Time
	UpdatedAt     time.Time
}

type UserBook struct {
	ID              uuid.UUID `gorm:"type:uuid;primaryKey"`
	UserID          uint      `gorm:"not null;uniqueIndex:idx_user_work"`
	WorkID          uuid.UUID `gorm:"type:uuid;not null;uniqueIndex:idx_user_work;index"`
	Status          string    `gorm:"not null;index;default:want"`
	Shelf           string    `gorm:"index"`
	CurrentPage     int
	TotalPages      int
	ProgressPercent int
	Rating          *int
	Notes           string
	StartedAt       *time.Time
	FinishedAt      *time.Time
	CreatedAt       time.Time
	UpdatedAt       time.Time
}

type WorkFactors struct {
	WorkID    uuid.UUID `gorm:"type:uuid;primaryKey"`
	Vector    datatypes.JSON
	UpdatedAt time.Time
}

func (WorkFactors) TableName() string { return "rec_work_factors" }

type UserFactors struct {
	UserID    uint `gorm:"primaryKey"`
	Vector    datatypes.JSON
	UpdatedAt time.Time
}

func (UserFactors) TableName() string { return "rec_user_factors" }

type WorkNeighbor struct {
	WorkID     uuid.UUID `gorm:"type:uuid;not null;index"`
	NeighborID uuid.UUID `gorm:"type:uuid;not null"`
	Score      float64
}

func (WorkNeighbor) TableName() string { return "rec_work_neighbors" }

type Dismissal struct {
	ID        uuid.UUID `gorm:"type:uuid;primaryKey"`
	UserID    uint      `gorm:"not null;uniqueIndex:idx_dismissal_user_work"`
	WorkID    uuid.UUID `gorm:"type:uuid;not null;uniqueIndex:idx_dismissal_user_work"`
	CreatedAt time.Time
}

func (Dismissal) TableName() string { return "rec_dismissals" }

// All lists every model, in dependency order, for AutoMigrate.
func All() []any {
	return []any{
		&User{}, &UserRole{}, &Identity{}, &PasswordCredential{}, &Session{}, &SessionRotation{}, &OneTimeToken{}, &AccountEmail{},
		&Work{}, &Edition{}, &Tag{},
		&Contribution{},
		&UserBook{},
		&WorkFactors{}, &UserFactors{}, &WorkNeighbor{}, &Dismissal{},
	}
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
