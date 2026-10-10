// Package middleware holds the request-context helpers handlers use to find
// the caller. Authentication itself is iam's httpauth (wired in
// internal/auth and internal/server); this package only translates its
// subject into LibraryZ's numeric user id.
package middleware

import (
	"context"
	"strconv"

	"github.com/kararnab/iam/v2/httpauth"
	"gorm.io/gorm"
)

// SubjectID is the iam subject id for a user id.
func SubjectID(id uint) string { return strconv.FormatUint(uint64(id), 10) }

// ParseSubjectID is the inverse of SubjectID. It rejects anything that
// isn't a canonical, non-zero decimal (no sign, no leading zeros).
func ParseSubjectID(s string) (uint, bool) {
	if s == "" || s[0] == '0' || len(s) > 20 {
		return 0, false
	}
	n, err := strconv.ParseUint(s, 10, strconv.IntSize)
	if err != nil {
		return 0, false
	}
	return uint(n), true
}

// UserID returns the authenticated user's id, set by httpauth's Protect for
// a valid bearer access token.
func UserID(ctx context.Context) (uint, bool) {
	sub, ok := httpauth.SubjectFrom(ctx)
	if !ok {
		return 0, false
	}
	return ParseSubjectID(sub.ID)
}

// IsModerator reports whether the user currently holds the "moderator"
// role, read from the database (not the access token), for checks made
// inside services. An unknown user is simply not a moderator.
func IsModerator(ctx context.Context, db *gorm.DB, userID uint) (bool, error) {
	var n int64
	err := db.WithContext(ctx).Table("user_roles").
		Where("user_id = ? AND role = ?", userID, "moderator").Count(&n).Error
	return n > 0, err
}
