package auth

import (
	"context"
	"encoding/json"
	"time"

	"github.com/kararnab/iam/v2/session"
	"gorm.io/gorm"
)

// Sessions implements session.Store over the sessions and
// session_rotations tables.
type Sessions struct{ db *gorm.DB }

var (
	_ session.Store  = (*Sessions)(nil)
	_ session.Purger = (*Sessions)(nil)
)

// NewSessions returns the session store.
func NewSessions(db *gorm.DB) *Sessions { return &Sessions{db: db} }

func toRow(s *session.Session) (*Session, error) {
	attrs, err := json.Marshal(s.Attrs)
	if err != nil {
		return nil, err
	}
	return &Session{
		ID: s.ID, SubjectID: s.SubjectID, Mode: string(s.Mode), TokenHash: s.TokenHash,
		CreatedAt: s.CreatedAt.UTC(), LastUsedAt: s.LastUsedAt.UTC(), ExpiresAt: s.ExpiresAt.UTC(),
		Attrs: attrs,
	}, nil
}

func fromRow(r *Session) *session.Session {
	var attrs map[string]string
	_ = json.Unmarshal(r.Attrs, &attrs) // null or malformed: no attributes
	return &session.Session{
		ID: r.ID, SubjectID: r.SubjectID, Mode: session.Mode(r.Mode), TokenHash: r.TokenHash,
		CreatedAt: r.CreatedAt, LastUsedAt: r.LastUsedAt, ExpiresAt: r.ExpiresAt,
		Attrs: attrs,
	}
}

// Create implements session.Store.
func (s *Sessions) Create(ctx context.Context, sess *session.Session) error {
	row, err := toRow(sess)
	if err != nil {
		return err
	}
	return s.db.WithContext(ctx).Create(row).Error
}

// Get implements session.Store.
func (s *Sessions) Get(ctx context.Context, id string) (*session.Session, error) {
	var row Session
	if found, err := takeOne(s.db.WithContext(ctx).Where("id = ?", id), &row); err != nil {
		return nil, err
	} else if !found {
		return nil, session.ErrNotFound
	}
	return fromRow(&row), nil
}

// GetByTokenHash implements session.Store: the current token first, then
// the rotated ones.
func (s *Sessions) GetByTokenHash(ctx context.Context, hash []byte) (*session.Session, session.TokenState, error) {
	db := s.db.WithContext(ctx)
	var row Session
	if found, err := takeOne(db.Where("token_hash = ?", hash), &row); err != nil {
		return nil, session.TokenState{}, err
	} else if found {
		return fromRow(&row), session.TokenState{}, nil
	}
	var rot SessionRotation
	if found, err := takeOne(db.Where("token_hash = ?", hash), &rot); err != nil {
		return nil, session.TokenState{}, err
	} else if !found {
		return nil, session.TokenState{}, session.ErrNotFound
	}
	sess, err := s.Get(ctx, rot.SessionID)
	if err != nil {
		return nil, session.TokenState{}, err
	}
	return sess, session.TokenState{RotatedAt: rot.RotatedAt}, nil
}

// Rotate implements session.Store. The compare-and-swap is a single
// UPDATE ... WHERE token_hash = old, so of two concurrent rotations only
// one matches a row (Postgres re-checks the WHERE after the row lock;
// sqlite serializes writers).
func (s *Sessions) Rotate(ctx context.Context, id string, oldHash, newHash []byte, at time.Time) error {
	return s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		res := tx.Model(&Session{}).Where("id = ? AND token_hash = ?", id, oldHash).
			Updates(map[string]any{"token_hash": newHash, "last_used_at": at.UTC()})
		if res.Error != nil {
			return res.Error
		}
		if res.RowsAffected == 0 {
			var n int64
			if err := tx.Model(&Session{}).Where("id = ?", id).Count(&n).Error; err != nil {
				return err
			}
			if n == 0 {
				return session.ErrNotFound
			}
			return session.ErrConflict
		}
		return tx.Create(&SessionRotation{TokenHash: oldHash, SessionID: id, RotatedAt: at.UTC()}).Error
	})
}

// Touch implements session.Store.
func (s *Sessions) Touch(ctx context.Context, id string, at time.Time) error {
	return s.db.WithContext(ctx).Model(&Session{}).Where("id = ?", id).Update("last_used_at", at.UTC()).Error
}

// Delete implements session.Store. Rotated hashes are deleted explicitly
// rather than relying on ON DELETE CASCADE, which sqlite only enforces
// with foreign_keys=ON.
func (s *Sessions) Delete(ctx context.Context, id string) error {
	return s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if err := tx.Where("session_id = ?", id).Delete(&SessionRotation{}).Error; err != nil {
			return err
		}
		return tx.Where("id = ?", id).Delete(&Session{}).Error
	})
}

// ListBySubject implements session.Store, oldest first.
func (s *Sessions) ListBySubject(ctx context.Context, subjectID string) ([]*session.Session, error) {
	var rows []Session
	if err := s.db.WithContext(ctx).Where("subject_id = ?", subjectID).
		Order("created_at, id").Find(&rows).Error; err != nil {
		return nil, err
	}
	out := make([]*session.Session, len(rows))
	for i := range rows {
		out[i] = fromRow(&rows[i])
	}
	return out, nil
}

// DeleteBySubject implements session.Store.
func (s *Sessions) DeleteBySubject(ctx context.Context, subjectID, exceptID string) (int, error) {
	var n int64
	err := s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		ids := tx.Model(&Session{}).Select("id").Where("subject_id = ? AND id <> ?", subjectID, exceptID)
		if err := tx.Where("session_id IN (?)", ids).Delete(&SessionRotation{}).Error; err != nil {
			return err
		}
		res := tx.Where("subject_id = ? AND id <> ?", subjectID, exceptID).Delete(&Session{})
		n = res.RowsAffected
		return res.Error
	})
	return int(n), err
}

// PurgeExpired implements session.Purger: it deletes sessions whose
// absolute expiry is at or before now (with their rotated hashes), and
// returns how many. iam never returns them (the manager checks expiry), so
// this only bounds table growth. Idle-expired sessions linger until their
// absolute expiry; they're just as unusable.
func (s *Sessions) PurgeExpired(ctx context.Context, now time.Time) (int, error) {
	var n int64
	err := s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		ids := tx.Model(&Session{}).Select("id").Where("expires_at <= ?", now.UTC())
		if err := tx.Where("session_id IN (?)", ids).Delete(&SessionRotation{}).Error; err != nil {
			return err
		}
		res := tx.Where("expires_at <= ?", now.UTC()).Delete(&Session{})
		n = res.RowsAffected
		return res.Error
	})
	return int(n), err
}
