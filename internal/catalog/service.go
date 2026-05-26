package catalog

import (
	"context"
	"errors"
	"io"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/kararnab/libraryZ/internal/storage"
	"gorm.io/gorm"
)

var ErrNotFound = errors.New("not found")

type Service struct {
	db    *gorm.DB
	store storage.Storage
}

func NewService(db *gorm.DB, store storage.Storage) *Service {
	return &Service{db: db, store: store}
}

func (s *Service) CreateWork(ctx context.Context, w *Work) error {
	if w.ID == uuid.Nil {
		w.ID = uuid.New()
	}
	return s.db.WithContext(ctx).Create(w).Error
}

func (s *Service) GetWork(ctx context.Context, id uuid.UUID) (*Work, error) {
	var w Work
	err := s.db.WithContext(ctx).Preload("Editions").Preload("Tags").First(&w, "id = ?", id).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, ErrNotFound
	}
	return &w, err
}

func (s *Service) ListWorks(ctx context.Context, limit, offset int) ([]Work, error) {
	var works []Work
	// Preload Editions so callers can show an accurate "N editions" count
	// in list views without an extra round-trip per row.
	q := s.db.WithContext(ctx).Preload("Editions").Order("created_at DESC")
	if limit > 0 {
		q = q.Limit(limit)
	}
	if offset > 0 {
		q = q.Offset(offset)
	}
	return works, q.Find(&works).Error
}

func (s *Service) GetEdition(ctx context.Context, id uuid.UUID) (*Edition, error) {
	var e Edition
	err := s.db.WithContext(ctx).First(&e, "id = ?", id).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, ErrNotFound
	}
	return &e, err
}

// AddEdition streams r into storage, then records the edition. If the bytes
// match an existing edition (same sha256), the existing one is returned.
func (s *Service) AddEdition(ctx context.Context, workID uuid.UUID, format, language string, uploadedBy uint, r io.Reader) (*Edition, error) {
	if _, err := s.GetWork(ctx, workID); err != nil {
		return nil, err
	}

	obj, err := s.store.Put(ctx, r)
	if err != nil {
		return nil, err
	}

	var existing Edition
	err = s.db.WithContext(ctx).Where("sha256 = ?", obj.SHA256).First(&existing).Error
	if err == nil {
		return &existing, nil
	}
	if !errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, err
	}

	ed := Edition{
		ID:               uuid.New(),
		WorkID:           workID,
		Format:           format,
		Language:         language,
		FileKey:          obj.Key,
		SizeBytes:        obj.Size,
		SHA256:           obj.SHA256,
		UploadedByUserID: uploadedBy,
		CreatedAt:        time.Now(),
	}
	if err := s.db.WithContext(ctx).Create(&ed).Error; err != nil {
		return nil, err
	}
	return &ed, nil
}

// SearchWorks finds works whose title/authors/description match q.
//
// Driver-aware: on Postgres uses the `search_vector` tsvector column +
// GIN index installed by MigratePostgresExtras; on sqlite (the test DB)
// falls back to `LOWER(col) LIKE LOWER(pattern)` across all three text
// columns. Both paths order by `created_at DESC` for parity — ts_rank
// ranking is a follow-up once there's a real corpus to evaluate against.
//
// Empty q is the handler's concern (returns 400); the service treats it
// as a no-op returning an empty slice rather than scanning the table.
func (s *Service) SearchWorks(ctx context.Context, q string, limit, offset int) ([]Work, error) {
	q = strings.TrimSpace(q)
	if q == "" {
		return []Work{}, nil
	}
	var works []Work
	db := s.db.WithContext(ctx).Preload("Editions").Order("created_at DESC")
	if s.db.Dialector.Name() == "postgres" {
		db = db.Where("search_vector @@ plainto_tsquery('simple', ?)", q)
	} else {
		pattern := "%" + strings.ToLower(q) + "%"
		db = db.Where(
			"LOWER(title) LIKE ? OR LOWER(authors) LIKE ? OR LOWER(description) LIKE ?",
			pattern, pattern, pattern,
		)
	}
	if limit > 0 {
		db = db.Limit(limit)
	}
	if offset > 0 {
		db = db.Offset(offset)
	}
	return works, db.Find(&works).Error
}

// OpenEdition returns the edition metadata, an open reader for its bytes, and
// the size of those bytes as reported by the storage backend (which may differ
// from ed.SizeBytes if the stored object has drifted — the handler uses this
// value, not the recorded one, to frame the response).
func (s *Service) OpenEdition(ctx context.Context, id uuid.UUID) (*Edition, io.ReadCloser, int64, error) {
	ed, err := s.GetEdition(ctx, id)
	if err != nil {
		return nil, nil, 0, err
	}
	rc, size, err := s.store.Get(ctx, ed.FileKey)
	if err != nil {
		return nil, nil, 0, err
	}
	return ed, rc, size, nil
}
