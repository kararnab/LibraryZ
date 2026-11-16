package auth

import (
	"encoding/json"
	"errors"
	"log"
	"net/http"

	"github.com/kararnab/iam/v2"
	"github.com/kararnab/iam/v2/httpauth"
	"github.com/kararnab/iam/v2/password"
	"github.com/kararnab/libraryZ/internal/middleware"
)

// msgWrongPassword is the 403 text for a wrong current password. Not a 401:
// the caller is signed in, and a 401 would make the app refresh its tokens
// and retry.
const msgWrongPassword = "current password is incorrect"

// ChangePassword (POST /me/password, authenticated) sets a new password
// after checking the current one (iam.Passwords). The current password is
// checked under the login throttles and keys, so this form can't guess
// faster than sign-in. On success the caller's other sessions end and any
// emailed reset links stop working; this session stays signed in.
//
// 204 changed · 400 bad body, or a new password the policy rejects (no
// attempt is counted) · 403 wrong current password · 429 throttled.
func (h *Handler) ChangePassword(w http.ResponseWriter, r *http.Request) {
	uid, ok := middleware.UserID(r.Context())
	sess, hasSession := httpauth.SessionFrom(r.Context())
	if !ok || !hasSession {
		http.Error(w, "unauthenticated", http.StatusUnauthorized)
		return
	}
	var req struct {
		CurrentPassword string `json:"current_password"`
		NewPassword     string `json:"new_password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	if req.CurrentPassword == "" {
		http.Error(w, "current password is required", http.StatusBadRequest)
		return
	}
	if len(req.CurrentPassword) > PasswordPolicy.MaxLength {
		http.Error(w, errMsgLongPassword, http.StatusBadRequest)
		return
	}
	var u User
	if err := h.auth.db.WithContext(r.Context()).Take(&u, uid).Error; err != nil {
		http.Error(w, "user not found", http.StatusNotFound)
		return
	}
	err := h.auth.Passwords.ChangePassword(r.Context(), iam.ChangePasswordRequest{
		SessionID:       sess.ID,
		Login:           u.Email,
		CurrentPassword: req.CurrentPassword,
		NewPassword:     req.NewPassword,
		Client:          h.auth.HTTP.ClientInfo(r),
	})
	var rl *iam.RateLimitError
	switch {
	case err == nil:
		w.WriteHeader(http.StatusNoContent)
	case errors.As(err, &rl):
		writeRateLimited(w, rl.RetryAfter)
	case errors.Is(err, password.ErrTooShort):
		http.Error(w, errMsgWeakPassword, http.StatusBadRequest)
	case errors.Is(err, password.ErrTooLong):
		http.Error(w, errMsgLongPassword, http.StatusBadRequest)
	case errors.Is(err, iam.ErrInvalidCredentials):
		http.Error(w, msgWrongPassword, http.StatusForbidden)
	case errors.Is(err, iam.ErrInvalidSession), errors.Is(err, iam.ErrSubjectDisabled):
		http.Error(w, "unauthenticated", http.StatusUnauthorized)
	case errors.Is(err, iam.ErrUnavailable):
		http.Error(w, "temporarily unavailable", http.StatusServiceUnavailable)
	default:
		log.Printf("auth: password change for user %d failed: %v", uid, err)
		http.Error(w, "internal error", http.StatusInternalServerError)
	}
}
