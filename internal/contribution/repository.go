package contribution

import (
	"time"

	"github.com/google/uuid"
	"gorm.io/datatypes"
	"gorm.io/gorm"
)

type Status string

const (
	StatusPending  Status = "pending"
	StatusApproved Status = "approved"
	StatusRejected Status = "rejected"
)

// Contribution is a proposed edit to a Work. The Patch column stores a
// flat partial-object of the form {"title": "...", "authors": "..."};
// only whitelisted keys (see service.allowedPatchFields) survive the
// Approve apply step. datatypes.JSON picks JSONB on Postgres and TEXT
// on sqlite so smoke tests stay portable.
type Contribution struct {
	ID            uuid.UUID      `gorm:"type:uuid;primaryKey" json:"id"`
	WorkID        uuid.UUID      `gorm:"type:uuid;not null;index" json:"work_id"`
	ContributorID uint           `gorm:"not null;index" json:"contributor_id"`
	Status        Status         `gorm:"not null;index;default:pending" json:"status"`
	Patch         datatypes.JSON `json:"patch"`
	ReviewerID    *uint          `gorm:"index" json:"reviewer_id,omitempty"`
	DecidedAt     *time.Time     `json:"decided_at,omitempty"`
	CreatedAt     time.Time      `json:"created_at"`
	UpdatedAt     time.Time      `json:"updated_at"`

	// ContributorName is populated by the service layer via a users-table
	// lookup (gorm:"-" so AutoMigrate ignores it). It's what the moderator
	// queue UI displays under each card — empty for users that no longer
	// exist, which the UI falls back to "user #ID" on.
	ContributorName string `gorm:"-" json:"contributor_name,omitempty"`

	// Current holds the target Work's present value for each field named in
	// Patch — i.e. the "old" side of the moderator-queue diff. Populated by
	// the service via a batch Works lookup (gorm:"-"); keys mirror Patch so
	// the UI can pair old↔new. Resolved at read time, so it reflects the
	// Work as it stands now (another approval may have moved it since
	// submission). Omitted from the wire when empty.
	Current map[string]any `gorm:"-" json:"current,omitempty"`
}

func Migrate(db *gorm.DB) error {
	return db.AutoMigrate(&Contribution{})
}
