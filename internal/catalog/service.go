package catalog

import (
	"context"
	"errors"
	"io"
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
	q := s.db.WithContext(ctx).Order("created_at DESC")
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

func (s *Service) OpenEdition(ctx context.Context, id uuid.UUID) (*Edition, io.ReadCloser, error) {
	ed, err := s.GetEdition(ctx, id)
	if err != nil {
		return nil, nil, err
	}
	rc, err := s.store.Get(ctx, ed.FileKey)
	if err != nil {
		return nil, nil, err
	}
	return ed, rc, nil
}
