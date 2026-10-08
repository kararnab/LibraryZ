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
	"gorm.io/gorm/clause"
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

// SearchWorks finds works whose title/subtitle/authors/description match q,
// most relevant first.
//
// Driver-aware: on Postgres it matches the weighted `search_vector` tsvector
// (GIN-indexed; see internal/migrations) with websearch_to_tsquery — so
// users can type `"exact phrase"`, `or`, and `-exclude` — and orders by
// ts_rank, which prefers title hits over author hits over description hits.
// On sqlite (the test DB) it falls back to `LOWER(col) LIKE` and
// approximates the ranking: title matches, then subtitle/author matches,
// then description-only matches. Ties break newest-first on both.
//
// Empty q is the handler's concern (returns 400); the service treats it
// as a no-op returning an empty slice rather than scanning the table.
func (s *Service) SearchWorks(ctx context.Context, q string, limit, offset int) ([]Work, error) {
	q = strings.TrimSpace(q)
	if q == "" {
		return []Work{}, nil
	}
	var works []Work
	db := s.db.WithContext(ctx).Preload("Editions")
	if s.db.Dialector.Name() == "postgres" {
		const tsq = "websearch_to_tsquery('simple', ?)"
		db = db.Where("search_vector @@ "+tsq, q).
			Clauses(orderBy("ts_rank(search_vector, "+tsq+") DESC, created_at DESC", q))
	} else {
		const like = `LIKE ? ESCAPE '\'`
		pattern := "%" + escapeLike(strings.ToLower(q)) + "%"
		db = db.Where(
			"LOWER(title) "+like+" OR LOWER(subtitle) "+like+" OR LOWER(authors) "+like+" OR LOWER(description) "+like,
			pattern, pattern, pattern, pattern,
		).Clauses(orderBy(
			"CASE WHEN LOWER(title) "+like+" THEN 0 WHEN LOWER(subtitle) "+like+" OR LOWER(authors) "+like+" THEN 1 ELSE 2 END, created_at DESC",
			pattern, pattern, pattern,
		))
	}
	if limit > 0 {
		db = db.Limit(limit)
	}
	if offset > 0 {
		db = db.Offset(offset)
	}
	return works, db.Find(&works).Error
}

// orderBy builds a parameterized ORDER BY. db.Order only accepts plain
// columns/strings, and a clause.OrderBy carrying an Expression drops any
// columns merged into it later — so the tiebreak lives in the same SQL.
func orderBy(sql string, vars ...any) clause.OrderBy {
	return clause.OrderBy{Expression: clause.Expr{SQL: sql, Vars: vars, WithoutParentheses: true}}
}

// likeEscaper escapes the LIKE metacharacters so user input matches
// literally; pair with `ESCAPE '\'` in the SQL.
var likeEscaper = strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`)

func escapeLike(s string) string { return likeEscaper.Replace(s) }

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
