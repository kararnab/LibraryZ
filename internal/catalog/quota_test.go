package catalog_test

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/kararnab/libraryZ/internal/catalog"
)

var testQuota = catalog.UploadQuota{
	Window:        24 * time.Hour,
	Standard:      catalog.QuotaLimit{Files: 3, Bytes: 1000},
	NewAccount:    catalog.QuotaLimit{Files: 2, Bytes: 100},
	NewAccountAge: 7 * 24 * time.Hour,
}

// quotaFixture is a fixture with testQuota, a settable clock, and user 1
// (verified, a month old, so on the standard tier unless a test changes it).
func quotaFixture(t *testing.T) (fixture, *time.Time) {
	t.Helper()
	f := newFixture(t)
	now := time.Date(2026, 10, 10, 12, 0, 0, 0, time.UTC)
	f.svc.SetUploadQuota(testQuota)
	f.svc.SetNow(func() time.Time { return now })
	created, verified := now.Add(-30*24*time.Hour), now.Add(-29*24*time.Hour)
	if err := f.db.Exec(`INSERT INTO users (id, email, name, disabled, created_at, email_verified_at) VALUES (1, 'u@x', 'U', false, ?, ?)`,
		created, verified).Error; err != nil {
		t.Fatal(err)
	}
	return f, &now
}

func quotaErr(t *testing.T, err error) *catalog.QuotaExceededError {
	t.Helper()
	var q *catalog.QuotaExceededError
	if !errors.As(err, &q) {
		t.Fatalf("want *QuotaExceededError, got %v", err)
	}
	return q
}

// Only recorded uploads count: a duplicate attempt doesn't use up a slot,
// and the next one is refused until the oldest ages out of the window.
func TestUploadQuotaCountsRecordedUploads(t *testing.T) {
	f, now := quotaFixture(t)
	w := f.work(t, "Q")
	ctx := context.Background()

	for i := range 3 {
		if _, err := f.add(w, "", fmt.Sprintf("book %d", i)); err != nil {
			t.Fatalf("upload %d: %v", i, err)
		}
		if i == 0 {
			*now = now.Add(time.Hour)
		}
	}
	var dup *catalog.DuplicateEditionError
	if _, err := f.add(w, "", "book 0"); !errors.As(err, &dup) {
		t.Fatalf("want duplicate, got %v", err)
	}

	st, err := f.svc.UploadQuotaStatus(ctx, 1)
	if err != nil {
		t.Fatal(err)
	}
	if st.Tier != catalog.TierStandard || st.FilesUsed != 3 || st.FilesLimit != 3 || st.BytesUsed != 18 {
		t.Fatalf("status = %+v", st)
	}
	q := quotaErr(t, f.svc.CheckUploadQuota(ctx, 1, 0))
	// The first upload was an hour before the others; it frees at +24h.
	if q.RetryAfter != 23*time.Hour {
		t.Fatalf("RetryAfter = %s, want 23h", q.RetryAfter)
	}
	quotaErr(t, func() error { _, err := f.add(w, "", "book 4"); return err }())

	*now = now.Add(23 * time.Hour)
	if _, err := f.add(w, "", "book 4"); err != nil {
		t.Fatalf("after the oldest aged out: %v", err)
	}
}

// RetryAfter waits for enough bytes to free up, not just the oldest upload.
func TestUploadQuotaBytesRetryAfter(t *testing.T) {
	f, now := quotaFixture(t)
	w := f.work(t, "Q")
	if _, err := f.add(w, "", string(bytes.Repeat([]byte("a"), 100))); err != nil {
		t.Fatal(err)
	}
	*now = now.Add(time.Hour)
	if _, err := f.add(w, "", string(bytes.Repeat([]byte("b"), 800))); err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	if err := f.svc.CheckUploadQuota(ctx, 1, 100); err != nil {
		t.Fatalf("100 more bytes fit: %v", err)
	}
	// 500 bytes need both earlier uploads gone: the second frees at +25h.
	if q := quotaErr(t, f.svc.CheckUploadQuota(ctx, 1, 500)); q.RetryAfter != 24*time.Hour {
		t.Fatalf("RetryAfter = %s, want 24h", q.RetryAfter)
	}
	// Bigger than the whole allowance: never fits, so a full window.
	if q := quotaErr(t, f.svc.CheckUploadQuota(ctx, 1, 2000)); q.RetryAfter != 24*time.Hour {
		t.Fatalf("RetryAfter = %s, want the window", q.RetryAfter)
	}
}

// Unverified or young accounts get the smaller allowance; moderators none.
func TestUploadQuotaTiers(t *testing.T) {
	f, now := quotaFixture(t)
	ctx := context.Background()
	tier := func() catalog.QuotaStatus {
		t.Helper()
		st, err := f.svc.UploadQuotaStatus(ctx, 1)
		if err != nil {
			t.Fatal(err)
		}
		return st
	}
	if st := tier(); st.Tier != catalog.TierStandard || st.FilesLimit != 3 || st.BytesLimit != 1000 {
		t.Fatalf("verified old account: %+v", st)
	}
	f.db.Exec(`UPDATE users SET email_verified_at = NULL WHERE id = 1`)
	if st := tier(); st.Tier != catalog.TierNewAccount || st.FilesLimit != 2 || st.BytesLimit != 100 {
		t.Fatalf("unverified: %+v", st)
	}
	f.db.Exec(`UPDATE users SET email_verified_at = ?, created_at = ? WHERE id = 1`, *now, now.Add(-time.Hour))
	if st := tier(); st.Tier != catalog.TierNewAccount {
		t.Fatalf("one hour old: %+v", st)
	}
	f.db.Exec(`INSERT INTO user_roles (user_id, role) VALUES (1, 'moderator')`)
	if st := tier(); st.Tier != catalog.TierModerator || !st.Unlimited {
		t.Fatalf("moderator: %+v", st)
	}
	if err := f.svc.CheckUploadQuota(ctx, 1, 1<<40); err != nil {
		t.Fatalf("moderator refused: %v", err)
	}
}

// A takedown doesn't hand the slot back: the upload still happened.
func TestUploadQuotaCountsTakenDownUploads(t *testing.T) {
	f, _ := quotaFixture(t)
	w := f.work(t, "Q")
	ed, err := f.add(w, "", "removed later")
	if err != nil {
		t.Fatal(err)
	}
	if err := f.svc.DeleteEdition(context.Background(), ed.ID, 1, "takedown"); err != nil {
		t.Fatal(err)
	}
	st, err := f.svc.UploadQuotaStatus(context.Background(), 1)
	if err != nil {
		t.Fatal(err)
	}
	if st.FilesUsed != 1 {
		t.Fatalf("FilesUsed = %d, want 1", st.FilesUsed)
	}
}

// No quota configured: everyone is unlimited.
func TestUploadQuotaZeroValueIsUnlimited(t *testing.T) {
	f := newFixture(t)
	st, err := f.svc.UploadQuotaStatus(context.Background(), 1)
	if err != nil || !st.Unlimited || st.Tier != catalog.TierUnlimited {
		t.Fatalf("status = %+v, %v", st, err)
	}
}
