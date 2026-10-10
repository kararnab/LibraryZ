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
	"github.com/kararnab/libraryZ/internal/middleware"
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

// DuplicateEditionError is returned by AddEdition when the uploaded bytes
// (after sanitization) are already stored as an edition, on this work or
// another one. Existing is that edition, so callers can point at it.
type DuplicateEditionError struct {
	Existing *Edition
}

func (e *DuplicateEditionError) Error() string {
	return "edition with identical content already exists: " + e.Existing.ID.String()
}

type Service struct {
	db    *gorm.DB
	store storage.Storage
	quota UploadQuota
	now   func() time.Time
}

func NewService(db *gorm.DB, store storage.Storage) *Service {
	return &Service{db: db, store: store, now: time.Now}
}

// CreateWork inserts w as a new work. The ID is always server-generated and
// associations (editions, tags) are never written from here: editions only
// come into existence through AddEdition, which is what ties a row to bytes
// actually in storage.
func (s *Service) CreateWork(ctx context.Context, w *Work) error {
	w.ID = uuid.New()
	w.Editions = nil
	w.Tags = nil
	return s.db.WithContext(ctx).Omit(clause.Associations).Create(w).Error
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

// AddEdition streams r (the sanitized bytes) into storage, then records the
// edition. sourceSHA is the sha256 of the bytes as uploaded, before
// sanitization ("" if unknown). If either the uploaded bytes or the stored
// bytes match an existing edition on any work, it returns a
// *DuplicateEditionError carrying that edition and records nothing. An
// upload that no longer fits uploadedBy's quota (see UploadQuota) gets a
// *QuotaExceededError; its blob is left for CollectGarbage.
func (s *Service) AddEdition(ctx context.Context, workID uuid.UUID, format, language string, uploadedBy uint, sourceSHA string, r io.Reader) (*Edition, error) {
	if _, err := s.GetWork(ctx, workID); err != nil {
		return nil, err
	}

	// Checked before Put so a duplicate PDF doesn't leave a freshly
	// re-serialized (and therefore never-deduped) blob behind in storage.
	if dup, err := s.findDuplicate(ctx, "", sourceSHA); err != nil {
		return nil, err
	} else if dup != nil {
		return nil, asDuplicate(dup)
	}

	obj, err := s.store.Put(ctx, r)
	if err != nil {
		return nil, err
	}

	if dup, err := s.findDuplicate(ctx, obj.SHA256, ""); err != nil {
		return nil, err
	} else if dup != nil {
		return nil, asDuplicate(dup)
	}

	var src *string
	if sourceSHA != "" {
		src = &sourceSHA
	}

	ed := Edition{
		ID:               uuid.New(),
		WorkID:           workID,
		Format:           format,
		Language:         language,
		FileKey:          obj.Key,
		SizeBytes:        obj.Size,
		SHA256:           obj.SHA256,
		SourceSHA256:     src,
		UploadedByUserID: uploadedBy,
	}
	err = s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		// The quota was checked before the body was read; check again with
		// the stored size, the user's row locked, so parallel uploads can't
		// all slip under it.
		if err := lockUser(tx, uploadedBy); err != nil {
			return err
		}
		now := s.now()
		st, err := s.quotaStatus(tx, uploadedBy, now)
		if err != nil {
			return err
		}
		if err := st.Admit(obj.Size); err != nil {
			return err
		}
		ed.CreatedAt = now
		return tx.Create(&ed).Error
	})
	var quotaErr *QuotaExceededError
	if errors.As(err, &quotaErr) {
		return nil, err
	}
	if err != nil {
		// A concurrent upload of the same bytes can win the race between the
		// lookups above and this insert; a unique index then rejects ours.
		// Re-check instead of parsing dialect-specific errors.
		if dup, findErr := s.findDuplicate(ctx, obj.SHA256, sourceSHA); findErr == nil && dup != nil {
			return nil, asDuplicate(dup)
		}
		return nil, err
	}
	return &ed, nil
}

// asDuplicate maps a matching edition to the error AddEdition returns: a
// takedown can't be undone by re-uploading the same file.
func asDuplicate(dup *Edition) error {
	if dup.DeletedAt.Valid {
		return ErrRemoved
	}
	return &DuplicateEditionError{Existing: dup}
}

// findDuplicate returns an edition whose stored-bytes hash equals sha or
// whose uploaded-bytes hash equals sourceSHA, or nil if none. An empty
// argument is not matched.
func (s *Service) findDuplicate(ctx context.Context, sha, sourceSHA string) (*Edition, error) {
	q := s.db.WithContext(ctx)
	switch {
	case sha != "" && sourceSHA != "":
		q = q.Where("sha256 = ? OR source_sha256 = ?", sha, sourceSHA)
	case sha != "":
		q = q.Where("sha256 = ?", sha)
	case sourceSHA != "":
		q = q.Where("source_sha256 = ?", sourceSHA)
	default:
		return nil, nil
	}
	// Unscoped: hashes stay unique across removed editions too, so a removed
	// one must be found here (and reported as ErrRemoved by asDuplicate)
	// rather than tripping the unique index.
	var e Edition
	err := q.Unscoped().First(&e).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &e, nil
}

// SearchWorks finds works whose title/subtitle/authors/description match q,
// most relevant first. A query shaped like an ISBN also matches the isbn
// column exactly, ignoring hyphens, spaces and case, and those hits rank
// first.
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
	// ISBN-shaped queries also match the isbn column; isbnFirst then
	// leads the ORDER BY. Both are empty for every other query.
	isbnOr, isbnFirst, isbnArgs := "", "", []any{}
	if isbn, ok := normalizeISBN(q); ok {
		isbnOr = " OR " + isbnColumn + " = ?"
		isbnFirst = "CASE WHEN " + isbnColumn + " = ? THEN 0 ELSE 1 END, "
		isbnArgs = []any{isbn}
	}
	if s.db.Dialector.Name() == "postgres" {
		const tsq = "websearch_to_tsquery('simple', ?)"
		db = db.Where("search_vector @@ "+tsq+isbnOr, append([]any{q}, isbnArgs...)...).
			Clauses(orderBy(isbnFirst+"ts_rank(search_vector, "+tsq+") DESC, created_at DESC", append(isbnArgs, q)...))
	} else {
		const like = `LIKE ? ESCAPE '\'`
		pattern := "%" + escapeLike(strings.ToLower(q)) + "%"
		db = db.Where(
			"LOWER(title) "+like+" OR LOWER(subtitle) "+like+" OR LOWER(authors) "+like+" OR LOWER(description) "+like+isbnOr,
			append([]any{pattern, pattern, pattern, pattern}, isbnArgs...)...,
		).Clauses(orderBy(
			isbnFirst+"CASE WHEN LOWER(title) "+like+" THEN 0 WHEN LOWER(subtitle) "+like+" OR LOWER(authors) "+like+" THEN 1 ELSE 2 END, created_at DESC",
			append(isbnArgs, pattern, pattern, pattern)...,
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

// isbnColumn is works.isbn as stored (typed by the uploader, so possibly
// hyphenated), normalized the same way as normalizeISBN.
const isbnColumn = "REPLACE(REPLACE(UPPER(isbn), '-', ''), ' ', '')"

// normalizeISBN reports whether q looks like an ISBN-10 or ISBN-13 once
// hyphens and spaces are dropped, and returns it in that form (upper-case
// X check digit). The check digit itself isn't validated: a typo'd ISBN
// just finds nothing.
func normalizeISBN(q string) (string, bool) {
	n := strings.ToUpper(strings.NewReplacer("-", "", " ", "").Replace(q))
	switch len(n) {
	case 10, 13:
	default:
		return "", false
	}
	for i, c := range n {
		if c >= '0' && c <= '9' || c == 'X' && len(n) == 10 && i == 9 {
			continue
		}
		return "", false
	}
	return n, true
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

// ErrForbidden is returned when the caller may not remove the work.
var ErrForbidden = errors.New("only a moderator, or the creator of a work with no editions, can remove it")

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
//
// Moderators can remove any work. Anyone else can only remove a work they
// created that has no editions at all — which is how the new-work upload
// flow cleans up after its first upload is rejected (e.g. a duplicate).
func (s *Service) DeleteWork(ctx context.Context, id uuid.UUID, by uint, reason string) error {
	if !validReason(reason) {
		return ErrReasonRequired
	}
	isMod, err := middleware.IsModerator(ctx, s.db, by)
	if err != nil {
		return err
	}
	if !isMod {
		var w Work
		if err := s.db.WithContext(ctx).Select("id", "created_by_user_id").First(&w, "id = ?", id).Error; err != nil {
			if errors.Is(err, gorm.ErrRecordNotFound) {
				return ErrNotFound
			}
			return err
		}
		var editions int64
		if err := s.db.WithContext(ctx).Unscoped().Model(&Edition{}).Where("work_id = ?", id).Count(&editions).Error; err != nil {
			return err
		}
		if w.CreatedByUserID == nil || *w.CreatedByUserID != by || editions > 0 {
			return ErrForbidden
		}
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
