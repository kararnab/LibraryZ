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
	ID           uint   `gorm:"primaryKey"`
	Email        string `gorm:"uniqueIndex"`
	Password     string
	Name         string
	IsModerator  bool
	TokenVersion int `gorm:"not null;default:0"`
}

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
		&User{}, &RefreshToken{},
		&Work{}, &Edition{}, &Tag{},
		&Contribution{},
		&UserBook{},
		&WorkFactors{}, &UserFactors{}, &WorkNeighbor{}, &Dismissal{},
	}
}
