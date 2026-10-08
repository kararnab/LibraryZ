package catalog

import (
	"context"
	"errors"
	"fmt"
	"io"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"
	"github.com/kararnab/libraryZ/internal/storage"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

var (
	ErrNotFound = errors.New("not found")
	// ErrRemoved is returned when uploading bytes identical to an edition a
	// moderator took down — a takedown shouldn't be undone by re-uploading.
	ErrRemoved = errors.New("this file was removed by a moderator and can't be re-uploaded")
)

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

	// Unscoped: sha256 is unique across removed rows too, so a removed
	// edition must be found here rather than tripping the unique index.
	var existing Edition
	err = s.db.WithContext(ctx).Unscoped().Where("sha256 = ?", obj.SHA256).First(&existing).Error
	if err == nil {
		if existing.DeletedAt.Valid {
			return nil, ErrRemoved
		}
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

// Download is an open edition plus what the handler needs to frame it.
type Download struct {
	Edition *Edition
	// WorkTitle / WorkAuthors name the downloaded file.
	WorkTitle   string
	WorkAuthors string
	Body        io.ReadCloser
	// Size is what the storage backend reports, which may differ from
	// Edition.SizeBytes if the stored object has drifted — the handler uses
	// this value, not the recorded one, to frame the response.
	Size int64
}

// OpenEdition loads the edition and its parent work's naming fields and opens
// the stored bytes. The caller must close Body.
func (s *Service) OpenEdition(ctx context.Context, id uuid.UUID) (*Download, error) {
	ed, err := s.GetEdition(ctx, id)
	if err != nil {
		return nil, err
	}
	var work struct{ Title, Authors string }
	if err := s.db.WithContext(ctx).Model(&Work{}).Select("title", "authors").
		Where("id = ?", ed.WorkID).Take(&work).Error; err != nil && !errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, err
	}
	rc, size, err := s.store.Get(ctx, ed.FileKey)
	if err != nil {
		return nil, err
	}
	return &Download{Edition: ed, WorkTitle: work.Title, WorkAuthors: work.Authors, Body: rc, Size: size}, nil
}

// maxDeleteReasonRunes caps the takedown note moderators attach.
const maxDeleteReasonRunes = 1000

// ErrReasonRequired is returned when a takedown has no (or an over-long)
// reason; the audit trail is the point of soft deletion.
var ErrReasonRequired = fmt.Errorf("a reason (1-%d characters) is required", maxDeleteReasonRunes)

func validReason(reason string) bool {
	n := utf8.RuneCountInString(strings.TrimSpace(reason))
	return n > 0 && n <= maxDeleteReasonRunes
}

func removal(by uint, reason string) map[string]any {
	return map[string]any{
		"deleted_at":    time.Now(),
		"deleted_by":    by,
		"delete_reason": strings.TrimSpace(reason),
	}
}

// DeleteWork takes a work down along with all its editions. The rows are
// soft-deleted (hidden from list/search/get/download, library and
// recommendations) and the blobs are purged later by CollectGarbage.
func (s *Service) DeleteWork(ctx context.Context, id uuid.UUID, by uint, reason string) error {
	if !validReason(reason) {
		return ErrReasonRequired
	}
	return s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		// Model(&Work{}) scopes to deleted_at IS NULL, so removing an
		// already-removed work is a 404, not a silent re-stamp.
		res := tx.Model(&Work{}).Where("id = ?", id).Updates(removal(by, reason))
		if res.Error != nil {
			return res.Error
		}
		if res.RowsAffected == 0 {
			return ErrNotFound
		}
		return tx.Model(&Edition{}).Where("work_id = ?", id).Updates(removal(by, reason)).Error
	})
}

// DeleteEdition takes a single edition down (see DeleteWork).
func (s *Service) DeleteEdition(ctx context.Context, id uuid.UUID, by uint, reason string) error {
	if !validReason(reason) {
		return ErrReasonRequired
	}
	res := s.db.WithContext(ctx).Model(&Edition{}).Where("id = ?", id).Updates(removal(by, reason))
	if res.Error != nil {
		return res.Error
	}
	if res.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}
