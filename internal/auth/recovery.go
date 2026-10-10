package auth

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"log/slog"
	"math"
	"net/http"
	"strings"
	"time"

	"github.com/kararnab/iam/v2"
	"github.com/kararnab/iam/v2/password"
	"github.com/kararnab/iam/v2/ratelimit"
	"github.com/kararnab/libraryZ/internal/middleware"
)

// Password reset and email verification over iam.Recovery. iam issues and
// checks the single-use tokens; LibraryZ stores them (tokens.go), emails
// them (emails.go, through onemailer), caps the emails per account, and
// records verified addresses (users.email_verified_at).
//
// Status codes (the clients rely on them):
//   - 202 a request was accepted (and an email may be on its way)
//   - 204 done, or nothing to do
//   - 400 bad body, or a new password the policy rejects (text is user-facing)
//   - 410 the token is unknown, used or expired
//   - 429 throttled (Retry-After)

// msgGone is the 410 text for any unusable token.
const msgGone = "this link has expired or was already used"

func writeRateLimited(w http.ResponseWriter, retryAfter time.Duration) {
	secs := int(math.Ceil(retryAfter.Seconds()))
	if secs < 1 {
		secs = 1
	}
	w.Header().Set("Retry-After", fmt.Sprint(secs))
	http.Error(w, fmt.Sprintf("too many attempts; try again in %d seconds", secs), http.StatusTooManyRequests)
}

func writeAccepted(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusAccepted)
	_, _ = w.Write([]byte(`{"status":"accepted"}` + "\n"))
}

// countResetAttempt counts a reset request that the email cap suppressed
// on iam's own reset throttle keys (recovery.go: "reset:login:<login>" and
// "reset:ip:<ip>" on the login limiters), as iam does for every request it
// sees, so a capped request is throttled exactly like any other.
func (a *Auth) countResetAttempt(ctx context.Context, login, ip string) (time.Duration, error) {
	type limitKey struct {
		limiter ratelimit.Limiter
		key     string
	}
	var keys []limitKey
	if a.perLogin != nil {
		keys = append(keys, limitKey{a.perLogin, "reset:login:" + login})
	}
	if a.perIP != nil && ip != "" {
		keys = append(keys, limitKey{a.perIP, "reset:ip:" + ip})
	}
	for _, k := range keys {
		r, err := k.limiter.Check(ctx, k.key)
		if err != nil {
			return 0, err
		}
		if !r.Allowed {
			return r.RetryAfter, nil
		}
	}
	for _, k := range keys {
		_, _ = k.limiter.Fail(ctx, k.key)
	}
	return 0, nil
}

// RequestPasswordReset (POST /auth/password-reset, anonymous) emails a reset
// link if the address has an account. The answer is 202 whether or not it
// does, and whether or not the account's email cap held the message back,
// so it can't be used to find out which addresses are registered.
func (h *Handler) RequestPasswordReset(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Email string `json:"email"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	email := password.NormalizeLogin(req.Email)
	if !validEmail(email) {
		http.Error(w, errMsgInvalidEmail, http.StatusBadRequest)
		return
	}
	client := h.auth.HTTP.ClientInfo(r)
	ctx := r.Context()

	// Check the account's cap before a token is issued: issuing replaces
	// the account's earlier token, which would break the link already in
	// their inbox.
	var u User
	release := func() {}
	found := h.auth.db.WithContext(ctx).Where("email = ?", email).Take(&u).Error == nil
	if found && !u.Disabled {
		reason, rel, err := h.reserveEmail(ctx, u.ID, KindPasswordReset)
		if err != nil {
			log.Printf("auth: reserving reset email for user %d: %v", u.ID, err)
			http.Error(w, "temporarily unavailable", http.StatusServiceUnavailable)
			return
		}
		if reason != "" {
			slog.Info("mail not sent", "event", KindPasswordReset+"_not_sent", "reason", reason, "user_id", u.ID)
			retry, err := h.auth.countResetAttempt(ctx, email, client.IP)
			switch {
			case err != nil:
				http.Error(w, "temporarily unavailable", http.StatusServiceUnavailable)
			case retry > 0:
				writeRateLimited(w, retry)
			default:
				writeAccepted(w)
			}
			return
		}
		release = rel
	}

	issued, err := h.auth.Recovery.StartPasswordReset(ctx, iam.PasswordResetRequest{Login: email, Client: client})
	var rl *iam.RateLimitError
	switch {
	case errors.As(err, &rl):
		release()
		writeRateLimited(w, rl.RetryAfter)
		return
	case errors.Is(err, iam.ErrUnavailable):
		release()
		http.Error(w, "temporarily unavailable", http.StatusServiceUnavailable)
		return
	case err != nil:
		release()
		log.Printf("auth: password reset request failed: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if issued != nil && found {
		h.send(KindPasswordReset, &u, issued.Token, ResetTokenTTL, release)
	} else {
		release()
	}
	writeAccepted(w)
}

// CompletePasswordReset (POST /auth/password-reset/complete, anonymous) sets
// a new password with a reset token and signs the account out everywhere.
// A password the policy rejects leaves the token usable.
func (h *Handler) CompletePasswordReset(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Token       string `json:"token"`
		NewPassword string `json:"new_password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	_, err := h.auth.Recovery.CompletePasswordReset(r.Context(), iam.CompletePasswordResetRequest{
		Token:       strings.TrimSpace(req.Token),
		NewPassword: req.NewPassword,
		Client:      h.auth.HTTP.ClientInfo(r),
	})
	switch {
	case err == nil:
		w.WriteHeader(http.StatusNoContent)
	case errors.Is(err, iam.ErrInvalidOneTimeToken):
		http.Error(w, msgGone, http.StatusGone)
	case errors.Is(err, password.ErrTooShort):
		http.Error(w, errMsgWeakPassword, http.StatusBadRequest)
	case errors.Is(err, password.ErrTooLong):
		http.Error(w, errMsgLongPassword, http.StatusBadRequest)
	case errors.Is(err, iam.ErrUnavailable):
		http.Error(w, "temporarily unavailable", http.StatusServiceUnavailable)
	default:
		log.Printf("auth: password reset failed: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
	}
}

// sendVerification emails u a verification link for their current
// address, within the account's cap. It returns the cap reason when capped.
func (h *Handler) sendVerification(r *http.Request, u *User) (capped string, err error) {
	reason, release, err := h.reserveEmail(r.Context(), u.ID, KindEmailVerification)
	if err != nil || reason != "" {
		return reason, err
	}
	issued, err := h.auth.Recovery.StartEmailVerification(r.Context(), iam.EmailVerificationRequest{
		SubjectID: middleware.SubjectID(u.ID),
		Email:     u.Email,
		Client:    h.auth.HTTP.ClientInfo(r),
	})
	if err != nil {
		release()
		return "", err
	}
	h.send(KindEmailVerification, u, issued.Token, VerificationTokenTTL, release)
	return "", nil
}

// RequestEmailVerification (POST /me/email-verification, authenticated)
// emails the caller a fresh verification link, replacing earlier ones.
// 204 when already verified; 429 when the account asked too recently.
func (h *Handler) RequestEmailVerification(w http.ResponseWriter, r *http.Request) {
	uid, ok := middleware.UserID(r.Context())
	if !ok {
		http.Error(w, "unauthenticated", http.StatusUnauthorized)
		return
	}
	var u User
	if err := h.auth.db.WithContext(r.Context()).Take(&u, uid).Error; err != nil {
		http.Error(w, "user not found", http.StatusNotFound)
		return
	}
	if u.EmailVerifiedAt != nil {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	capped, err := h.sendVerification(r, &u)
	switch {
	case errors.Is(err, iam.ErrInvalidEmail):
		http.Error(w, errMsgInvalidEmail, http.StatusBadRequest)
	case err != nil:
		log.Printf("auth: email verification request for user %d failed: %v", uid, err)
		http.Error(w, "internal error", http.StatusInternalServerError)
	case capped == "cooldown":
		writeRateLimited(w, EmailCooldown)
	case capped != "":
		writeRateLimited(w, time.Hour)
	default:
		writeAccepted(w)
	}
}

// CompleteEmailVerification (POST /auth/email-verification/complete,
// anonymous: the token is the proof) marks the address as verified, as
// long as it is still the account's address.
func (h *Handler) CompleteEmailVerification(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Token string `json:"token"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	verified, err := h.auth.Recovery.CompleteEmailVerification(r.Context(), strings.TrimSpace(req.Token), h.auth.HTTP.ClientInfo(r))
	switch {
	case errors.Is(err, iam.ErrInvalidOneTimeToken):
		http.Error(w, msgGone, http.StatusGone)
		return
	case errors.Is(err, iam.ErrUnavailable):
		http.Error(w, "temporarily unavailable", http.StatusServiceUnavailable)
		return
	case err != nil:
		log.Printf("auth: email verification failed: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	uid, ok := middleware.ParseSubjectID(verified.SubjectID)
	if !ok {
		http.Error(w, msgGone, http.StatusGone)
		return
	}
	res := h.auth.db.WithContext(r.Context()).Model(&User{}).
		Where("id = ? AND email = ?", uid, verified.Email).
		Update("email_verified_at", h.auth.now())
	if res.Error != nil {
		log.Printf("auth: recording verified email for user %d: %v", uid, res.Error)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if res.RowsAffected == 0 {
		// The account's address changed since the link was sent.
		http.Error(w, msgGone, http.StatusGone)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]string{"email": verified.Email})
}
