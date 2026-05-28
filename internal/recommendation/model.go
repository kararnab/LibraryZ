package recommendation

import (
	"time"

	"github.com/google/uuid"
	"gorm.io/datatypes"
	"gorm.io/gorm"
)

// The recommendation package owns four precomputed tables. The factor tables
// hold the latent vectors produced by the implicit-ALS trainer; the neighbor
// table is the item-item "similarity table" derived from the work vectors; the
// dismissals table is the user feedback signal. Vectors are datatypes.JSON
// (`[]float64`) so they're portable across Postgres and the sqlite test DB —
// no pgvector, which would break test parity.

// WorkFactors is a work's latent vector.
type WorkFactors struct {
	WorkID    uuid.UUID      `gorm:"type:uuid;primaryKey" json:"work_id"`
	Vector    datatypes.JSON `json:"vector"`
	UpdatedAt time.Time      `json:"updated_at"`
}

func (WorkFactors) TableName() string { return "rec_work_factors" }

// UserFactors is a user's latent vector.
type UserFactors struct {
	UserID    uint           `gorm:"primaryKey" json:"user_id"`
	Vector    datatypes.JSON `json:"vector"`
	UpdatedAt time.Time      `json:"updated_at"`
}

func (UserFactors) TableName() string { return "rec_user_factors" }

// WorkNeighbor is one top-N item-item similarity edge (cosine over work
// vectors). Powers "Because you liked X" explanations and item-based cold start.
type WorkNeighbor struct {
	WorkID     uuid.UUID `gorm:"type:uuid;not null;index" json:"work_id"`
	NeighborID uuid.UUID `gorm:"type:uuid;not null" json:"neighbor_id"`
	Score      float64   `json:"score"`
}

func (WorkNeighbor) TableName() string { return "rec_work_neighbors" }

// Dismissal records that a user hid a recommendation; excluded from future
// results. Unique on (user_id, work_id) so re-dismissing is idempotent.
type Dismissal struct {
	ID        uuid.UUID `gorm:"type:uuid;primaryKey" json:"id"`
	UserID    uint      `gorm:"not null;uniqueIndex:idx_dismissal_user_work" json:"user_id"`
	WorkID    uuid.UUID `gorm:"type:uuid;not null;uniqueIndex:idx_dismissal_user_work" json:"work_id"`
	CreatedAt time.Time `json:"created_at"`
}

func (Dismissal) TableName() string { return "rec_dismissals" }

func Migrate(db *gorm.DB) error {
	return db.AutoMigrate(&WorkFactors{}, &UserFactors{}, &WorkNeighbor{}, &Dismissal{})
}
