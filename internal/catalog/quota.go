package catalog

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/kararnab/libraryZ/internal/middleware"
	"gorm.io/gorm"
)

// QuotaLimit caps how many files, and how many stored bytes, one account
// may upload within the quota window.
type QuotaLimit struct {
	Files int
	Bytes int64
}

// UploadQuota is the per-account upload allowance. It counts editions the
// user actually uploaded (by uploaded_by_user_id and created_at, taken-down
// ones included), so rejected attempts — duplicates, failed safety checks,
// cancelled uploads — don't use it up. The window is rolling, not a clock
// hour, and the count lives in the database, so every replica sees the same
// numbers. Kong's per-IP limit stays in front as a coarse flood guard.
//
// The zero value (Window == 0) means no quota.
type UploadQuota struct {
	Window time.Duration
	// Standard applies to verified accounts older than NewAccountAge.
	Standard QuotaLimit
	// NewAccount applies to accounts younger than NewAccountAge or whose
	// email isn't verified.
	NewAccount    QuotaLimit
	NewAccountAge time.Duration
}

// Quota tiers, as reported in QuotaStatus.Tier.
const (
	TierStandard   = "standard"
	TierNewAccount = "new"
	TierModerator  = "moderator"
	TierUnlimited  = "unlimited"
)

// QuotaStatus is a user's standing against their upload quota (the body of
// GET /me/upload-quota).
type QuotaStatus struct {
	Tier string `json:"tier"`
	// Unlimited is true for moderators and when no quota is configured;
	// the limit and window fields are then zero.
	Unlimited     bool  `json:"unlimited"`
	WindowSeconds int64 `json:"window_seconds"`
	FilesLimit    int   `json:"files_limit"`
	FilesUsed     int   `json:"files_used"`
	BytesLimit    int64 `json:"bytes_limit"`
	BytesUsed     int64 `json:"bytes_used"`
	// NextFreeAt is when the oldest upload in the window ages out and gives
	// back its file and bytes; absent when nothing is in the window.
	NextFreeAt *time.Time `json:"next_free_at,omitempty"`

	uploads []windowUpload
	now     time.Time
}

type windowUpload struct {
	CreatedAt time.Time
	SizeBytes int64
}

// QuotaExceededError is returned when an upload would go over the user's
// quota. RetryAfter is how long until it would fit.
type QuotaExceededError struct {
	RetryAfter time.Duration
	Status     QuotaStatus
}

func (e *QuotaExceededError) Error() string {
	return fmt.Sprintf("upload limit reached: %d files or %s per %s",
		e.Status.FilesLimit, formatBytes(e.Status.BytesLimit), formatWindow(time.Duration(e.Status.WindowSeconds)*time.Second))
}

// SetUploadQuota sets the per-account upload quota (zero value = none).
func (s *Service) SetUploadQuota(q UploadQuota) { s.quota = q }

// UploadQuotaStatus reports where userID stands against their quota.
func (s *Service) UploadQuotaStatus(ctx context.Context, userID uint) (QuotaStatus, error) {
	return s.quotaStatus(s.db.WithContext(ctx), userID, s.now())
}

// CheckUploadQuota returns a *QuotaExceededError if an upload of size bytes
// (0 if unknown) would not fit in userID's quota right now. It's the cheap
// check made before reading an upload's body; AddEdition re-checks with the
// stored size when it records the edition.
func (s *Service) CheckUploadQuota(ctx context.Context, userID uint, size int64) error {
	st, err := s.UploadQuotaStatus(ctx, userID)
	if err != nil {
		return err
	}
	return st.Admit(size)
}

func (s *Service) quotaStatus(db *gorm.DB, userID uint, now time.Time) (QuotaStatus, error) {
	q := s.quota
	if q.Window <= 0 {
		return QuotaStatus{Tier: TierUnlimited, Unlimited: true}, nil
	}
	isMod, err := middleware.IsModerator(db.Statement.Context, db, userID)
	if err != nil {
		return QuotaStatus{}, err
	}
	if isMod {
		return QuotaStatus{Tier: TierModerator, Unlimited: true}, nil
	}

	var user struct {
		CreatedAt       time.Time
		EmailVerifiedAt *time.Time
	}
	if err := db.Table("users").Select("created_at", "email_verified_at").
		Where("id = ?", userID).Take(&user).Error; err != nil && !errors.Is(err, gorm.ErrRecordNotFound) {
		return QuotaStatus{}, err
	}
	tier, limit := TierStandard, q.Standard
	if user.EmailVerifiedAt == nil || now.Sub(user.CreatedAt) < q.NewAccountAge {
		tier, limit = TierNewAccount, q.NewAccount
	}

	// Unscoped: an upload a moderator took down still counted when it was made.
	var uploads []windowUpload
	if err := db.Unscoped().Model(&Edition{}).Select("created_at", "size_bytes").
		Where("uploaded_by_user_id = ? AND created_at > ?", userID, now.Add(-q.Window)).
		Order("created_at").Find(&uploads).Error; err != nil {
		return QuotaStatus{}, err
	}

	st := QuotaStatus{
		Tier:          tier,
		WindowSeconds: int64(q.Window / time.Second),
		FilesLimit:    limit.Files,
		BytesLimit:    limit.Bytes,
		FilesUsed:     len(uploads),
		uploads:       uploads,
		now:           now,
	}
	for _, u := range uploads {
		st.BytesUsed += u.SizeBytes
	}
	if len(uploads) > 0 {
		t := uploads[0].CreatedAt.Add(q.Window)
		st.NextFreeAt = &t
	}
	return st, nil
}

// Admit returns a *QuotaExceededError unless one more upload of size bytes
// fits. RetryAfter is when enough of the window's uploads have aged out for
// it to fit (the whole window if it can never fit, i.e. size > BytesLimit).
func (st QuotaStatus) Admit(size int64) error {
	if st.Unlimited {
		return nil
	}
	window := time.Duration(st.WindowSeconds) * time.Second
	files, bytes := st.FilesUsed, st.BytesUsed
	for k := 0; k <= len(st.uploads); k++ {
		if k > 0 {
			files--
			bytes -= st.uploads[k-1].SizeBytes
		}
		if files+1 <= st.FilesLimit && bytes+size <= st.BytesLimit {
			if k == 0 {
				return nil
			}
			return &QuotaExceededError{RetryAfter: st.uploads[k-1].CreatedAt.Add(window).Sub(st.now), Status: st}
		}
	}
	return &QuotaExceededError{RetryAfter: window, Status: st}
}

// lockUser serializes quota checks for one user on Postgres, so concurrent
// uploads are counted one after the other (sqlite serializes writes anyway).
func lockUser(tx *gorm.DB, userID uint) error {
	if tx.Dialector.Name() != "postgres" {
		return nil
	}
	return tx.Exec("SELECT 1 FROM users WHERE id = ? FOR UPDATE", userID).Error
}

func formatBytes(n int64) string {
	switch {
	case n >= 1<<30 && n%(1<<30) == 0:
		return fmt.Sprintf("%d GiB", n>>30)
	case n >= 1<<20:
		return fmt.Sprintf("%d MiB", n>>20)
	default:
		return fmt.Sprintf("%d bytes", n)
	}
}

func formatWindow(d time.Duration) string {
	if d%(24*time.Hour) == 0 {
		if d == 24*time.Hour {
			return "day"
		}
		return fmt.Sprintf("%d days", d/(24*time.Hour))
	}
	if d == time.Hour {
		return "hour"
	}
	return d.String()
}
