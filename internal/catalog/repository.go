package catalog

import (
	"time"

	"github.com/google/uuid"
	"gorm.io/gorm"
)

// Work is the canonical record for a book/document — the abstract thing
// (e.g. "Moby-Dick"), independent of any particular file.
type Work struct {
	ID       uuid.UUID `gorm:"type:uuid;primaryKey" json:"id"`
	Title    string    `gorm:"not null;index" json:"title"`
	Subtitle string    `json:"subtitle,omitempty"`
	// Authors is semicolon-separated for Phase 1; normalized into its own
	// table in Phase 2 alongside crowdsourced contributions.
	Authors         string    `json:"authors,omitempty"`
	Description     string    `json:"description,omitempty"`
	Language        string    `json:"language,omitempty"`
	PublicationYear int       `json:"publication_year,omitempty"`
	ISBN            string    `gorm:"index" json:"isbn,omitempty"`
	OpenLibraryID   string    `gorm:"index" json:"openlibrary_id,omitempty"`
	CreatedAt       time.Time `json:"created_at"`
	UpdatedAt       time.Time `json:"updated_at"`
	Removal

	Editions []Edition `gorm:"foreignKey:WorkID;constraint:OnDelete:CASCADE" json:"editions,omitempty"`
	Tags     []Tag     `gorm:"many2many:work_tags;" json:"tags,omitempty"`
}

// Edition is a specific file attached to a Work: one Work can have many
// editions (pdf, epub, mobi, translations, etc.). SHA256 is unique so
// identical bytes uploaded twice collapse to one storage object.
type Edition struct {
	ID               uuid.UUID `gorm:"type:uuid;primaryKey" json:"id"`
	WorkID           uuid.UUID `gorm:"type:uuid;not null;index" json:"work_id"`
	Format           string    `gorm:"not null" json:"format"`
	Language         string    `json:"language,omitempty"`
	FileKey          string    `gorm:"not null;index" json:"-"`
	SizeBytes        int64     `json:"size_bytes"`
	SHA256           string    `gorm:"size:64;uniqueIndex" json:"sha256"`
	UploadedByUserID uint      `gorm:"not null;index" json:"uploaded_by"`
	CreatedAt        time.Time `json:"created_at"`
	Removal
}

// Removal is the moderator takedown record embedded in Work and Edition.
// DeletedAt is gorm.DeletedAt, so every GORM query on those models
// (First/Find/Preload) skips removed rows automatically; raw
// Table("works") queries elsewhere must add `deleted_at IS NULL`
// themselves. Rows are kept — not hard-deleted — so takedowns stay
// auditable and reversible; the blob itself is purged by CollectGarbage
// after a retention period.
type Removal struct {
	DeletedAt    gorm.DeletedAt `gorm:"index" json:"-"`
	DeletedBy    *uint          `json:"-"`
	DeleteReason string         `json:"-"`
}

type Tag struct {
	ID    uuid.UUID `gorm:"type:uuid;primaryKey" json:"id"`
	Name  string    `gorm:"uniqueIndex;not null" json:"name"`
	Works []Work    `gorm:"many2many:work_tags;" json:"-"`
}
