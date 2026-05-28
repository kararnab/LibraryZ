package library

import (
	"time"

	"github.com/google/uuid"
	"github.com/kararnab/libraryZ/internal/catalog"
	"gorm.io/gorm"
)

type Status string

const (
	StatusWant    Status = "want"
	StatusReading Status = "reading"
	StatusRead    Status = "read"
)

func (s Status) valid() bool {
	switch s {
	case StatusWant, StatusReading, StatusRead:
		return true
	}
	return false
}

// UserBook is one user's personal-library entry for a Work: their reading
// status, an optional free-form shelf, reading progress, a rating, and private
// notes. Exactly one row per (user, work) — enforced by the composite
// uniqueIndex, which is also the upsert key.
type UserBook struct {
	ID              uuid.UUID `gorm:"type:uuid;primaryKey" json:"id"`
	UserID          uint      `gorm:"not null;uniqueIndex:idx_user_work" json:"user_id"`
	WorkID          uuid.UUID `gorm:"type:uuid;not null;uniqueIndex:idx_user_work;index" json:"work_id"`
	Status          Status    `gorm:"not null;index;default:want" json:"status"`
	Shelf           string    `gorm:"index" json:"shelf,omitempty"`
	CurrentPage     int       `json:"current_page,omitempty"`
	TotalPages      int       `json:"total_pages,omitempty"`
	ProgressPercent int       `json:"progress_percent,omitempty"`
	// Rating is a pointer so "unrated" (nil) is distinct from a 0; it also lets
	// an upsert request clear a previously-set rating by sending null.
	Rating     *int       `json:"rating,omitempty"` // 1..5 when set
	Notes      string     `json:"notes,omitempty"`
	StartedAt  *time.Time `json:"started_at,omitempty"`
	FinishedAt *time.Time `json:"finished_at,omitempty"`
	CreatedAt  time.Time  `json:"created_at"`
	UpdatedAt  time.Time  `json:"updated_at"`

	// Work is enriched on read (gorm:"-") so the My Library list renders the
	// title/authors/editions without a second round-trip. Unlike the
	// contribution package — which kept itself decoupled from catalog — the
	// library legitimately *composes* works, so we embed the full Work. The
	// dependency is acyclic: catalog never imports library.
	Work *catalog.Work `gorm:"-" json:"work,omitempty"`
}

func Migrate(db *gorm.DB) error {
	return db.AutoMigrate(&UserBook{})
}
