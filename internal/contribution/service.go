package contribution

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/google/uuid"
	"gorm.io/datatypes"
	"gorm.io/gorm"
)

var (
	ErrNotFound       = errors.New("contribution not found")
	ErrWorkNotFound   = errors.New("work not found")
	ErrEmptyPatch     = errors.New("patch is empty")
	ErrAlreadyDecided = errors.New("contribution already decided")
)

// allowedPatchFields are the Work columns a contribution may modify.
// Anything outside this set is silently dropped on Approve. Keys are the
// JSON field names callers send (same as GET /works/{id}); values are the
// actual works column names. These diverge for openlibrary_id: GORM names
// the OpenLibraryID column `open_library_id`, so the patch key must be
// translated before it reaches an UPDATE (otherwise approving such an edit
// hits a non-existent column).
var allowedPatchFields = map[string]string{
	"title":            "title",
	"subtitle":         "subtitle",
	"authors":          "authors",
	"description":      "description",
	"language":         "language",
	"publication_year": "publication_year",
	"isbn":             "isbn",
	"openlibrary_id":   "open_library_id",
}

type Service struct {
	db *gorm.DB
}

func NewService(db *gorm.DB) *Service {
	return &Service{db: db}
}

// Submit records a new pending contribution. The patch is stored
// verbatim; whitelist filtering happens on Approve so the original
// proposal stays auditable.
func (s *Service) Submit(ctx context.Context, workID uuid.UUID, contributorID uint, patch map[string]any) (*Contribution, error) {
	if len(patch) == 0 {
		return nil, ErrEmptyPatch
	}
	var count int64
	if err := s.db.WithContext(ctx).Table("works").Where("id = ?", workID).Count(&count).Error; err != nil {
		return nil, err
	}
	if count == 0 {
		return nil, ErrWorkNotFound
	}
	raw, err := json.Marshal(patch)
	if err != nil {
		return nil, err
	}
	c := &Contribution{
		ID:            uuid.New(),
		WorkID:        workID,
		ContributorID: contributorID,
		Status:        StatusPending,
		Patch:         datatypes.JSON(raw),
	}
	if err := s.db.WithContext(ctx).Create(c).Error; err != nil {
		return nil, err
	}
	return c, nil
}

func (s *Service) Get(ctx context.Context, id uuid.UUID) (*Contribution, error) {
	var c Contribution
	err := s.db.WithContext(ctx).First(&c, "id = ?", id).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	one := []Contribution{c}
	s.enrich(ctx, one)
	return &one[0], nil
}

// populateContributorNames batch-loads the users referenced by the given
// contributions and sets ContributorName in place. Best-effort: a failed
// users query is swallowed so the queue still renders (with blank names
// the UI shows "user #ID" as a fallback). One query for the whole batch
// regardless of how many distinct contributor_ids appear.
func (s *Service) populateContributorNames(ctx context.Context, cs []Contribution) {
	if len(cs) == 0 {
		return
	}
	seen := make(map[uint]struct{}, len(cs))
	for _, c := range cs {
		seen[c.ContributorID] = struct{}{}
	}
	ids := make([]uint, 0, len(seen))
	for id := range seen {
		ids = append(ids, id)
	}

	type row struct {
		ID   uint
		Name string
	}
	var rows []row
	if err := s.db.WithContext(ctx).
		Table("users").
		Select("id, name").
		Where("id IN ?", ids).
		Find(&rows).Error; err != nil {
		return
	}
	nameByID := make(map[uint]string, len(rows))
	for _, r := range rows {
		nameByID[r.ID] = r.Name
	}
	for i := range cs {
		if name, ok := nameByID[cs[i].ContributorID]; ok {
			cs[i].ContributorName = name
		}
	}
}

// worksFields mirrors the whitelisted, contribution-editable columns of
// the works table. Declared locally rather than importing internal/catalog
// so the contribution package stays decoupled from the catalog model — the
// column names are the contract (and match allowedPatchFields).
type worksFields struct {
	ID              string `gorm:"column:id"`
	Title           string `gorm:"column:title"`
	Subtitle        string `gorm:"column:subtitle"`
	Authors         string `gorm:"column:authors"`
	Description     string `gorm:"column:description"`
	Language        string `gorm:"column:language"`
	PublicationYear int    `gorm:"column:publication_year"`
	ISBN            string `gorm:"column:isbn"`
	OpenLibraryID   string `gorm:"column:open_library_id"`
}

func (w worksFields) field(name string) any {
	switch name {
	case "title":
		return w.Title
	case "subtitle":
		return w.Subtitle
	case "authors":
		return w.Authors
	case "description":
		return w.Description
	case "language":
		return w.Language
	case "publication_year":
		return w.PublicationYear
	case "isbn":
		return w.ISBN
	case "openlibrary_id":
		return w.OpenLibraryID
	}
	return nil
}

// populateCurrentValues batch-loads the target Works and fills each
// contribution's Current map with the present value of every field named
// in its Patch — the "old" side of the diff. One query for the whole
// batch. Best-effort: a failed lookup or unparseable patch is skipped so
// the queue still renders (the UI treats a missing old value as "(empty)").
func (s *Service) populateCurrentValues(ctx context.Context, cs []Contribution) {
	if len(cs) == 0 {
		return
	}
	seen := make(map[uuid.UUID]struct{}, len(cs))
	for _, c := range cs {
		seen[c.WorkID] = struct{}{}
	}
	ids := make([]uuid.UUID, 0, len(seen))
	for id := range seen {
		ids = append(ids, id)
	}

	var rows []worksFields
	if err := s.db.WithContext(ctx).
		Table("works").
		Select("id, title, subtitle, authors, description, language, publication_year, isbn, open_library_id").
		Where("id IN ?", ids).
		Find(&rows).Error; err != nil {
		return
	}
	byID := make(map[string]worksFields, len(rows))
	for _, r := range rows {
		byID[r.ID] = r
	}

	for i := range cs {
		w, ok := byID[cs[i].WorkID.String()]
		if !ok || len(cs[i].Patch) == 0 {
			continue
		}
		var patch map[string]any
		if err := json.Unmarshal(cs[i].Patch, &patch); err != nil {
			continue
		}
		current := make(map[string]any, len(patch))
		for k := range patch {
			current[k] = w.field(k)
		}
		cs[i].Current = current
	}
}

// enrich runs the read-time population helpers in one place so every read
// path (List / Get / Approve / Reject) returns fully-decorated rows.
func (s *Service) enrich(ctx context.Context, cs []Contribution) {
	s.populateContributorNames(ctx, cs)
	s.populateCurrentValues(ctx, cs)
}

// ListFilter narrows List results. Zero-valued fields are ignored.
type ListFilter struct {
	Status        Status
	ContributorID *uint
	Limit         int
	Offset        int
}

func (s *Service) List(ctx context.Context, f ListFilter) ([]Contribution, error) {
	var cs []Contribution
	q := s.db.WithContext(ctx).Order("created_at DESC")
	if f.Status != "" {
		q = q.Where("status = ?", f.Status)
	}
	if f.ContributorID != nil {
		q = q.Where("contributor_id = ?", *f.ContributorID)
	}
	if f.Limit > 0 {
		q = q.Limit(f.Limit)
	}
	if f.Offset > 0 {
		q = q.Offset(f.Offset)
	}
	if err := q.Find(&cs).Error; err != nil {
		return nil, err
	}
	s.enrich(ctx, cs)
	return cs, nil
}

// Approve applies the contribution's whitelisted patch fields to the
// target Work and marks the contribution approved. Returns
// ErrAlreadyDecided if the contribution isn't pending — including when
// another moderator decides it concurrently (see transition).
func (s *Service) Approve(ctx context.Context, id uuid.UUID, reviewerID uint) (*Contribution, error) {
	return s.decide(ctx, id, reviewerID, StatusApproved, func(tx *gorm.DB, c *Contribution) error {
		var patch map[string]any
		if len(c.Patch) > 0 {
			if err := json.Unmarshal(c.Patch, &patch); err != nil {
				return err
			}
		}
		// Defense-in-depth: Submit now validates patches, but rows queued
		// before that check existed may still carry unknown keys.
		updates := make(map[string]any, len(patch))
		for k, v := range patch {
			if col, ok := allowedPatchFields[k]; ok {
				updates[col] = v
			}
		}
		if len(updates) == 0 {
			return nil
		}
		updates["updated_at"] = time.Now()
		return tx.Table("works").Where("id = ?", c.WorkID).Updates(updates).Error
	})
}

// Reject marks the contribution rejected without touching the Work.
func (s *Service) Reject(ctx context.Context, id uuid.UUID, reviewerID uint) (*Contribution, error) {
	return s.decide(ctx, id, reviewerID, StatusRejected, nil)
}

// decide moves a pending contribution to `to` and, only if that transition
// wins, runs apply — all in one transaction so a crash or apply failure
// rolls the status back too.
//
// The transition is a conditional UPDATE (`WHERE status = 'pending'`)
// rather than read-check-Save: under Postgres READ COMMITTED two
// moderators could otherwise both observe `pending` and both proceed,
// double-applying the patch or racing approve against reject. The row
// lock taken by the UPDATE makes the second one wait, re-check the
// predicate, and affect zero rows. Portable to sqlite, which serializes
// writers anyway.
func (s *Service) decide(ctx context.Context, id uuid.UUID, reviewerID uint, to Status, apply func(*gorm.DB, *Contribution) error) (*Contribution, error) {
	var out Contribution
	err := s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		now := time.Now()
		res := tx.Model(&Contribution{}).
			Where("id = ? AND status = ?", id, StatusPending).
			Updates(map[string]any{
				"status":      to,
				"reviewer_id": reviewerID,
				"decided_at":  now,
				"updated_at":  now,
			})
		if res.Error != nil {
			return res.Error
		}
		if res.RowsAffected == 0 {
			// Either it doesn't exist or someone else already decided it.
			var count int64
			if err := tx.Model(&Contribution{}).Where("id = ?", id).Count(&count).Error; err != nil {
				return err
			}
			if count == 0 {
				return ErrNotFound
			}
			return ErrAlreadyDecided
		}
		if err := tx.First(&out, "id = ?", id).Error; err != nil {
			return err
		}
		if apply != nil {
			return apply(tx, &out)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	one := []Contribution{out}
	s.enrich(ctx, one)
	return &one[0], nil
}
