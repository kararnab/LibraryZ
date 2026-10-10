package auth

import (
	"context"
	"errors"
	"time"

	"github.com/kararnab/iam/v2/onetime"
	"gorm.io/gorm"
)

// Tokens is iam's onetime.Store over GORM: password-reset and
// email-verification tokens. Checked by iam's storetest.Tokens suite.
type Tokens struct {
	db *gorm.DB
}

// NewTokens returns a token store over db.
func NewTokens(db *gorm.DB) *Tokens { return &Tokens{db: db} }

var _ onetime.Store = (*Tokens)(nil)

func tokenFromRow(r *OneTimeToken) *onetime.Token {
	t := &onetime.Token{
		ID:        r.ID,
		TokenHash: r.TokenHash,
		Purpose:   onetime.Purpose(r.Purpose),
		SubjectID: r.SubjectID,
		Login:     r.Login,
		Email:     r.Email,
		CreatedAt: r.CreatedAt,
		ExpiresAt: r.ExpiresAt,
	}
	if r.UsedAt != nil {
		t.UsedAt = *r.UsedAt
	}
	return t
}

// Create persists a new token.
func (s *Tokens) Create(ctx context.Context, t *onetime.Token) error {
	row := &OneTimeToken{
		ID:        t.ID,
		TokenHash: t.TokenHash,
		Purpose:   string(t.Purpose),
		SubjectID: t.SubjectID,
		Login:     t.Login,
		Email:     t.Email,
		CreatedAt: t.CreatedAt,
		ExpiresAt: t.ExpiresAt,
	}
	if !t.UsedAt.IsZero() {
		used := t.UsedAt
		row.UsedAt = &used
	}
	return s.db.WithContext(ctx).Create(row).Error
}

// GetByTokenHash returns a token, used or not, or onetime.ErrNotFound.
func (s *Tokens) GetByTokenHash(ctx context.Context, hash []byte) (*onetime.Token, error) {
	var row OneTimeToken
	err := s.db.WithContext(ctx).Where("token_hash = ?", hash).Take(&row).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, onetime.ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return tokenFromRow(&row), nil
}

// Consume marks the token used at `at`, but only if it has this purpose and
// is still unused and unexpired. A single conditional UPDATE, so of two
// concurrent calls at most one succeeds.
func (s *Tokens) Consume(ctx context.Context, hash []byte, purpose onetime.Purpose, at time.Time) (*onetime.Token, error) {
	res := s.db.WithContext(ctx).Model(&OneTimeToken{}).
		Where("token_hash = ? AND purpose = ? AND used_at IS NULL AND expires_at > ?", hash, string(purpose), at).
		Update("used_at", at)
	if res.Error != nil {
		return nil, res.Error
	}
	if res.RowsAffected == 0 {
		return nil, onetime.ErrInvalid
	}
	t, err := s.GetByTokenHash(ctx, hash)
	if errors.Is(err, onetime.ErrNotFound) {
		return nil, onetime.ErrInvalid
	}
	return t, err
}

// DeleteBySubject removes a subject's tokens of one purpose.
func (s *Tokens) DeleteBySubject(ctx context.Context, subjectID string, purpose onetime.Purpose) error {
	return s.db.WithContext(ctx).
		Where("subject_id = ? AND purpose = ?", subjectID, string(purpose)).
		Delete(&OneTimeToken{}).Error
}

// PurgeExpired deletes tokens that expired before now (used ones too: a
// used token is never usable again). Returns how many were removed.
func (s *Tokens) PurgeExpired(ctx context.Context, now time.Time) (int, error) {
	res := s.db.WithContext(ctx).Where("expires_at <= ?", now).Delete(&OneTimeToken{})
	return int(res.RowsAffected), res.Error
}
