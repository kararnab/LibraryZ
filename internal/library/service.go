package library

import (
	"context"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/kararnab/libraryZ/internal/catalog"
	"gorm.io/gorm"
)

var (
	ErrNotFound        = errors.New("library entry not found")
	ErrWorkNotFound    = errors.New("work not found")
	ErrInvalidStatus   = errors.New("invalid status")
	ErrInvalidRating   = errors.New("invalid rating")
	ErrInvalidProgress = errors.New("invalid progress percent")
)

type Service struct {
	db *gorm.DB
}

func NewService(db *gorm.DB) *Service {
	return &Service{db: db}
}

// UpsertRequest is a partial patch: every field is a pointer so an absent
// field is left untouched, while a present field (including an explicit null
// Rating) is applied. PUT /me/library/{work_id} decodes into this.
type UpsertRequest struct {
	Status          *string `json:"status"`
	Shelf           *string `json:"shelf"`
	CurrentPage     *int    `json:"current_page"`
	TotalPages      *int    `json:"total_pages"`
	ProgressPercent *int    `json:"progress_percent"`
	Rating          *int    `json:"rating"`
	Notes           *string `json:"notes"`
}

// Upsert creates or updates the calling user's entry for a work. On first
// touch the entry defaults to status "want"; subsequent calls patch only the
// fields present in req. Validates the enum/rating/percent before writing.
// Status transitions auto-stamp StartedAt (->reading) and FinishedAt (->read),
// idempotently — an existing stamp is never overwritten.
func (s *Service) Upsert(ctx context.Context, userID uint, workID uuid.UUID, req UpsertRequest) (*UserBook, error) {
	var count int64
	if err := s.db.WithContext(ctx).Table("works").Where("id = ?", workID).Count(&count).Error; err != nil {
		return nil, err
	}
	if count == 0 {
		return nil, ErrWorkNotFound
	}

	var ub UserBook
	err := s.db.WithContext(ctx).
		Where("user_id = ? AND work_id = ?", userID, workID).
		First(&ub).Error
	isNew := errors.Is(err, gorm.ErrRecordNotFound)
	if err != nil && !isNew {
		return nil, err
	}
	if isNew {
		ub = UserBook{ID: uuid.New(), UserID: userID, WorkID: workID, Status: StatusWant}
	}

	if req.Status != nil {
		st := Status(*req.Status)
		if !st.valid() {
			return nil, ErrInvalidStatus
		}
		s.applyStatus(&ub, st, req.ProgressPercent)
	}
	if req.Shelf != nil {
		ub.Shelf = *req.Shelf
	}
	if req.CurrentPage != nil {
		ub.CurrentPage = *req.CurrentPage
	}
	if req.TotalPages != nil {
		ub.TotalPages = *req.TotalPages
	}
	if req.ProgressPercent != nil {
		if *req.ProgressPercent < 0 || *req.ProgressPercent > 100 {
			return nil, ErrInvalidProgress
		}
		ub.ProgressPercent = *req.ProgressPercent
	}
	if req.Rating != nil {
		if *req.Rating < 1 || *req.Rating > 5 {
			return nil, ErrInvalidRating
		}
		r := *req.Rating
		ub.Rating = &r
	}
	if req.Notes != nil {
		ub.Notes = *req.Notes
	}

	if isNew {
		if err := s.db.WithContext(ctx).Create(&ub).Error; err != nil {
			return nil, err
		}
	} else {
		if err := s.db.WithContext(ctx).Save(&ub).Error; err != nil {
			return nil, err
		}
	}

	one := []UserBook{ub}
	s.enrich(ctx, one)
	return &one[0], nil
}

// applyStatus sets the status and, on a transition, stamps the lifecycle
// timestamps. Reaching "read" also forces progress to 100% unless the same
// request set an explicit percent (which the caller validates separately).
func (s *Service) applyStatus(ub *UserBook, st Status, explicitPercent *int) {
	now := time.Now()
	if st == StatusReading && ub.StartedAt == nil {
		ub.StartedAt = &now
	}
	if st == StatusRead {
		if ub.StartedAt == nil {
			ub.StartedAt = &now
		}
		ub.FinishedAt = &now
		if explicitPercent == nil {
			ub.ProgressPercent = 100
		}
	}
	ub.Status = st
}

func (s *Service) Get(ctx context.Context, userID uint, workID uuid.UUID) (*UserBook, error) {
	var ub UserBook
	err := s.db.WithContext(ctx).
		Where("user_id = ? AND work_id = ?", userID, workID).
		First(&ub).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	one := []UserBook{ub}
	s.enrich(ctx, one)
	return &one[0], nil
}

// ListFilter narrows List results. Zero-valued fields (other than UserID,
// which is always required by the handler) are ignored.
type ListFilter struct {
	UserID uint
	Status Status
	Shelf  string
	Limit  int
	Offset int
}

func (s *Service) List(ctx context.Context, f ListFilter) ([]UserBook, error) {
	var ubs []UserBook
	q := s.db.WithContext(ctx).
		Where("user_id = ?", f.UserID).
		Order("updated_at DESC")
	if f.Status != "" {
		q = q.Where("status = ?", f.Status)
	}
	if f.Shelf != "" {
		q = q.Where("shelf = ?", f.Shelf)
	}
	if f.Limit > 0 {
		q = q.Limit(f.Limit)
	}
	if f.Offset > 0 {
		q = q.Offset(f.Offset)
	}
	if err := q.Find(&ubs).Error; err != nil {
		return nil, err
	}
	s.enrich(ctx, ubs)
	return ubs, nil
}

// Delete removes the user's entry for a work. Returns ErrNotFound if there was
// nothing to delete, so the handler can 404 rather than 200 a no-op.
func (s *Service) Delete(ctx context.Context, userID uint, workID uuid.UUID) error {
	res := s.db.WithContext(ctx).
		Where("user_id = ? AND work_id = ?", userID, workID).
		Delete(&UserBook{})
	if res.Error != nil {
		return res.Error
	}
	if res.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

// enrich batch-loads the target Works (with their editions) and attaches each
// to the matching entry's Work field. One query for the whole batch.
// Best-effort: a failed lookup leaves Work nil and the UI falls back to the
// work id (mirrors contribution.populateCurrentValues).
func (s *Service) enrich(ctx context.Context, ubs []UserBook) {
	if len(ubs) == 0 {
		return
	}
	seen := make(map[uuid.UUID]struct{}, len(ubs))
	for _, ub := range ubs {
		seen[ub.WorkID] = struct{}{}
	}
	ids := make([]uuid.UUID, 0, len(seen))
	for id := range seen {
		ids = append(ids, id)
	}

	var works []catalog.Work
	if err := s.db.WithContext(ctx).
		Preload("Editions").
		Where("id IN ?", ids).
		Find(&works).Error; err != nil {
		return
	}
	byID := make(map[uuid.UUID]*catalog.Work, len(works))
	for i := range works {
		byID[works[i].ID] = &works[i]
	}
	for i := range ubs {
		if w, ok := byID[ubs[i].WorkID]; ok {
			ubs[i].Work = w
		}
	}
}
