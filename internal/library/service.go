package library

import (
	"context"
	"errors"
	"fmt"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"
	"github.com/kararnab/libraryZ/internal/catalog"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

var (
	ErrNotFound        = errors.New("library entry not found")
	ErrWorkNotFound    = errors.New("work not found")
	ErrInvalidStatus   = errors.New("invalid status")
	ErrInvalidRating   = errors.New("invalid rating")
	ErrInvalidProgress = errors.New("invalid progress percent")
	ErrNotesTooLong    = fmt.Errorf("notes must be at most %d characters", MaxNotesRunes)
	ErrShelfTooLong    = fmt.Errorf("shelf must be at most %d characters", MaxShelfRunes)
	ErrInvalidPages    = fmt.Errorf("pages must be between 0 and %d", MaxPages)
)

// Bounds on an entry's free-form fields. Generous: they stop abuse, not
// readers (notes match a work's description).
const (
	MaxNotesRunes = 20000
	MaxShelfRunes = 100
	MaxPages      = 1_000_000
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
//
// Fields come in groups (status; progress = current/total pages + percent;
// rating; shelf; notes), each with an optional stamp in Stamps saying when
// the change was made. A group is written only if its stamp is newer than
// the stored one, so writes queued offline on several devices merge per
// group, newest first, in any order and any number of times.
type UpsertRequest struct {
	Status          *string `json:"status"`
	Shelf           *string `json:"shelf"`
	CurrentPage     *int    `json:"current_page"`
	TotalPages      *int    `json:"total_pages"`
	ProgressPercent *int    `json:"progress_percent"`
	Rating          *int    `json:"rating"`
	Notes           *string `json:"notes"`

	Stamps Stamps `json:"stamps"`
	// NotesBase is the notes stamp the edit started from. When another
	// device changed the notes since, the text that loses is kept in
	// NotesOther instead of being dropped.
	NotesBase *string `json:"notes_base"`
	// ResolveNotes clears NotesOther (the reader chose).
	ResolveNotes bool `json:"resolve_notes"`
}

// Stamps are the per-group stamps of an UpsertRequest; empty means now.
type Stamps struct {
	Status   string `json:"status,omitempty"`
	Progress string `json:"progress,omitempty"`
	Rating   string `json:"rating,omitempty"`
	Shelf    string `json:"shelf,omitempty"`
	Notes    string `json:"notes,omitempty"`
}

func (r UpsertRequest) validate() error {
	if r.Status != nil && !Status(*r.Status).valid() {
		return ErrInvalidStatus
	}
	if r.ProgressPercent != nil && (*r.ProgressPercent < 0 || *r.ProgressPercent > 100) {
		return ErrInvalidProgress
	}
	if r.Rating != nil && (*r.Rating < 1 || *r.Rating > 5) {
		return ErrInvalidRating
	}
	for _, p := range []*int{r.CurrentPage, r.TotalPages} {
		if p != nil && (*p < 0 || *p > MaxPages) {
			return ErrInvalidPages
		}
	}
	if r.Notes != nil && utf8.RuneCountInString(*r.Notes) > MaxNotesRunes {
		return ErrNotesTooLong
	}
	if r.Shelf != nil && utf8.RuneCountInString(*r.Shelf) > MaxShelfRunes {
		return ErrShelfTooLong
	}
	// Only ever compared, but stored nowhere else to bound it.
	if r.NotesBase != nil && *r.NotesBase != "" && !stampRe.MatchString(*r.NotesBase) {
		return ErrInvalidStamp
	}
	return nil
}

// Upsert creates or updates the calling user's entry for a work. On first
// touch the entry defaults to status "want"; later calls patch only the
// groups present in req whose stamps are newer than what's stored. A work
// removed from the library after a change was stamped stays removed.
// Status transitions auto-stamp StartedAt (->reading) and FinishedAt (->read),
// idempotently — an existing stamp is never overwritten.
func (s *Service) Upsert(ctx context.Context, userID uint, workID uuid.UUID, req UpsertRequest) (*UserBook, error) {
	if err := req.validate(); err != nil {
		return nil, err
	}
	now := time.Now()
	stamp := func(st string) (string, error) { return checkStamp(st, now) }
	st := map[string]string{}
	for group, raw := range map[string]string{
		"status": req.Stamps.Status, "progress": req.Stamps.Progress, "rating": req.Stamps.Rating,
		"shelf": req.Stamps.Shelf, "notes": req.Stamps.Notes,
	} {
		v, err := stamp(raw)
		if err != nil {
			return nil, err
		}
		st[group] = v
	}

	var count int64
	if err := s.db.WithContext(ctx).Table("works").Where("id = ? AND deleted_at IS NULL", workID).Count(&count).Error; err != nil {
		return nil, err
	}
	if count == 0 {
		return nil, ErrWorkNotFound
	}

	var out UserBook
	err := s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		q := tx
		if tx.Dialector.Name() == "postgres" {
			q = tx.Clauses(clause.Locking{Strength: "UPDATE"})
		}
		var ub UserBook
		err := q.Where("user_id = ? AND work_id = ?", userID, workID).First(&ub).Error
		isNew := errors.Is(err, gorm.ErrRecordNotFound)
		if err != nil && !isNew {
			return err
		}
		if isNew {
			// Every group starts at the removal's stamp, if any: changes made
			// before the work was taken out don't count.
			var rm Removal
			floor := ""
			if err := tx.Where("user_id = ? AND work_id = ?", userID, workID).Limit(1).Find(&rm).Error; err != nil {
				return err
			}
			floor = rm.Stamp
			ub = UserBook{
				ID: uuid.New(), UserID: userID, WorkID: workID, Status: StatusWant,
				StatusStamp: floor, ProgressStamp: floor, RatingStamp: floor, ShelfStamp: floor, NotesStamp: floor,
			}
			if floor != "" && !req.newerThan(st, floor) {
				return ErrNotFound
			}
		}

		applied := mergeInto(&ub, req, st, now)
		if isNew {
			if err := tx.Create(&ub).Error; err != nil {
				return err
			}
			if err := tx.Where("user_id = ? AND work_id = ?", userID, workID).Delete(&Removal{}).Error; err != nil {
				return err
			}
		} else if applied {
			if err := tx.Save(&ub).Error; err != nil {
				return err
			}
		}
		out = ub
		return nil
	})
	if err != nil {
		return nil, err
	}
	one := []UserBook{out}
	s.enrich(ctx, one)
	return &one[0], nil
}

// newerThan reports whether any group present in r is stamped after floor.
func (r UpsertRequest) newerThan(st map[string]string, floor string) bool {
	present := map[string]bool{
		"status":   r.Status != nil,
		"progress": r.CurrentPage != nil || r.TotalPages != nil || r.ProgressPercent != nil,
		"rating":   r.Rating != nil,
		"shelf":    r.Shelf != nil,
		"notes":    r.Notes != nil,
	}
	for g, ok := range present {
		if ok && st[g] > floor {
			return true
		}
	}
	return false
}

// mergeInto applies each group of req whose stamp beats ub's; reports
// whether anything changed.
func mergeInto(ub *UserBook, req UpsertRequest, st map[string]string, now time.Time) bool {
	changed := false
	progressSet := req.CurrentPage != nil || req.TotalPages != nil || req.ProgressPercent != nil
	if progressSet && st["progress"] > ub.ProgressStamp {
		if req.CurrentPage != nil {
			ub.CurrentPage = *req.CurrentPage
		}
		if req.TotalPages != nil {
			ub.TotalPages = *req.TotalPages
		}
		if req.ProgressPercent != nil {
			ub.ProgressPercent = *req.ProgressPercent
		}
		ub.ProgressStamp = st["progress"]
		changed = true
	}
	if req.Status != nil && st["status"] > ub.StatusStamp {
		applyStatus(ub, Status(*req.Status), now)
		ub.StatusStamp = st["status"]
		// Reaching "read" means all of it, unless this request said how much
		// (or a newer progress already did).
		if ub.Status == StatusRead && req.ProgressPercent == nil && st["status"] > ub.ProgressStamp {
			ub.ProgressPercent = 100
			ub.ProgressStamp = st["status"]
		}
		changed = true
	}
	if req.Rating != nil && st["rating"] > ub.RatingStamp {
		r := *req.Rating
		ub.Rating = &r
		ub.RatingStamp = st["rating"]
		changed = true
	}
	if req.Shelf != nil && st["shelf"] > ub.ShelfStamp {
		ub.Shelf = *req.Shelf
		ub.ShelfStamp = st["shelf"]
		changed = true
	}
	if req.Notes != nil {
		n := *req.Notes
		// Edited without seeing the stored text: whichever loses is kept.
		blind := req.NotesBase != nil && *req.NotesBase != ub.NotesStamp && ub.NotesStamp != ""
		if st["notes"] > ub.NotesStamp {
			if blind && ub.Notes != "" && ub.Notes != n {
				ub.NotesOther = ub.Notes
			}
			ub.Notes = n
			ub.NotesStamp = st["notes"]
			changed = true
		} else if blind && n != "" && n != ub.Notes {
			ub.NotesOther = n
			changed = true
		}
	}
	if req.ResolveNotes && ub.NotesOther != "" {
		ub.NotesOther = ""
		changed = true
	}
	return changed
}

// applyStatus sets the status and, on a transition, stamps the lifecycle
// timestamps.
func applyStatus(ub *UserBook, st Status, now time.Time) {
	if st == StatusReading && ub.StartedAt == nil {
		ub.StartedAt = &now
	}
	if st == StatusRead {
		if ub.StartedAt == nil {
			ub.StartedAt = &now
		}
		ub.FinishedAt = &now
	}
	ub.Status = st
}

func (s *Service) Get(ctx context.Context, userID uint, workID uuid.UUID) (*UserBook, error) {
	var ub UserBook
	err := s.db.WithContext(ctx).
		Where("user_id = ? AND work_id = ?", userID, workID).
		Where(liveWork).
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

// liveWork hides entries whose work a moderator removed. The rows are kept
// (the removal is reversible) but stop showing up in the library.
const liveWork = "work_id IN (SELECT id FROM works WHERE deleted_at IS NULL)"

func (s *Service) List(ctx context.Context, f ListFilter) ([]UserBook, error) {
	var ubs []UserBook
	q := s.db.WithContext(ctx).
		Where("user_id = ?", f.UserID).
		Where(liveWork).
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

// Delete removes the user's entry for a work, as of stamp (empty = now),
// and remembers when, so older offline changes can't bring it back. A
// change stamped after the removal (made elsewhere before this synced)
// keeps the entry, which is then returned so the caller can show it again;
// nil means it was removed. Returns ErrNotFound if there was nothing to
// delete, so the handler can 404 rather than 200 a no-op.
func (s *Service) Delete(ctx context.Context, userID uint, workID uuid.UUID, stamp string) (*UserBook, error) {
	now := time.Now()
	stamp, err := checkStamp(stamp, now)
	if err != nil {
		return nil, err
	}
	var kept *UserBook
	err = s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		q := tx
		if tx.Dialector.Name() == "postgres" {
			q = tx.Clauses(clause.Locking{Strength: "UPDATE"})
		}
		var ub UserBook
		err := q.Where("user_id = ? AND work_id = ?", userID, workID).First(&ub).Error
		found := err == nil
		if err != nil && !errors.Is(err, gorm.ErrRecordNotFound) {
			return err
		}
		if found && ub.latestStamp() > stamp {
			kept = &ub
			return nil
		}
		var rm Removal
		if err := tx.Where("user_id = ? AND work_id = ?", userID, workID).Limit(1).Find(&rm).Error; err != nil {
			return err
		}
		if rm.Stamp < stamp {
			rm = Removal{UserID: userID, WorkID: workID, Stamp: stamp}
			if err := tx.Save(&rm).Error; err != nil {
				return err
			}
		}
		if !found {
			return ErrNotFound
		}
		return tx.Delete(&ub).Error
	})
	if err != nil || kept == nil {
		return nil, err
	}
	one := []UserBook{*kept}
	s.enrich(ctx, one)
	return &one[0], nil
}

func (ub *UserBook) latestStamp() string {
	m := ub.StatusStamp
	for _, s := range []string{ub.ProgressStamp, ub.RatingStamp, ub.ShelfStamp, ub.NotesStamp} {
		if s > m {
			m = s
		}
	}
	return m
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
