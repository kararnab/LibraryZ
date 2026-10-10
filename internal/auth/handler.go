package auth

import (
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"math"
	"net/http"
	"slices"
	"strings"
	"time"

	"github.com/gorilla/mux"
	"github.com/kararnab/iam/v2"
	"github.com/kararnab/iam/v2/httpauth"
	"github.com/kararnab/iam/v2/password"
	"github.com/kararnab/iam/v2/session"
	"github.com/kararnab/libraryZ/internal/middleware"
)

type HealthResponse struct {
	Status string `json:"status"`
	Time   string `json:"time"`
}

type Handler struct {
	auth *Auth
}

func NewHandler(a *Auth) *Handler {
	return &Handler{auth: a}
}

// Error messages for 4xx responses. They're shown to users verbatim by the
// clients, so keep them short and safe.
var (
	errMsgInvalidEmail = "a valid email is required"
	errMsgWeakPassword = fmt.Sprintf("password must be at least %d characters", PasswordPolicy.MinLength)
	errMsgLongPassword = "password is too long"
	errMsgEmailExists  = "email already registered"
)

type credentials struct {
	Email    string `json:"email"`
	Password string `json:"password"`
	Name     string `json:"name"`
}

// TokenResponse is what signup, login and refresh return. Tokens travel in
// the body only (no Authorization response header).
type TokenResponse struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	TokenType    string `json:"token_type"`
	// ExpiresIn is the access token's lifetime in seconds.
	ExpiresIn int `json:"expires_in"`
}

func (h *Handler) SignUp(w http.ResponseWriter, r *http.Request) {
	var c credentials
	if err := json.NewDecoder(r.Body).Decode(&c); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	email := password.NormalizeLogin(c.Email)
	if !validEmail(email) {
		http.Error(w, errMsgInvalidEmail, http.StatusBadRequest)
		return
	}

	res, err := h.auth.Service.SignUp(r.Context(), iam.SignUpRequest{
		Provider: password.ProviderName,
		Params:   map[string]string{"username": email, "password": c.Password},
		Profile:  map[string]string{"name": c.Name},
		Client:   h.auth.HTTP.ClientInfo(r),
	})
	switch {
	case errors.Is(err, password.ErrTooShort):
		http.Error(w, errMsgWeakPassword, http.StatusBadRequest)
		return
	case errors.Is(err, password.ErrTooLong):
		http.Error(w, errMsgLongPassword, http.StatusBadRequest)
		return
	case errors.Is(err, iam.ErrAlreadyRegistered):
		http.Error(w, errMsgEmailExists, http.StatusConflict)
		return
	case err != nil:
		log.Printf("auth: signup failed: %v", err)
		http.Error(w, "internal server error", http.StatusInternalServerError)
		return
	}

	h.writeTokens(w, http.StatusCreated, res.AccessToken, res.RefreshToken)
}

func (h *Handler) Login(w http.ResponseWriter, r *http.Request) {
	var c credentials
	if err := json.NewDecoder(r.Body).Decode(&c); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	res, err := h.auth.Service.Login(r.Context(), iam.AuthRequest{
		Provider: password.ProviderName,
		Params:   map[string]string{"username": c.Email, "password": c.Password},
		Mode:     session.ModeBearer,
		Client:   h.auth.HTTP.ClientInfo(r),
	})
	if err != nil {
		h.loginError(w, err)
		return
	}
	h.writeTokens(w, http.StatusOK, res.AccessToken, res.RefreshToken)
}

func (h *Handler) loginError(w http.ResponseWriter, err error) {
	var rl *iam.RateLimitError
	switch {
	case errors.As(err, &rl):
		secs := int(math.Ceil(rl.RetryAfter.Seconds()))
		w.Header().Set("Retry-After", fmt.Sprint(secs))
		http.Error(w, fmt.Sprintf("too many attempts; try again in %d seconds", secs), http.StatusTooManyRequests)
	case errors.Is(err, iam.ErrInvalidCredentials),
		errors.Is(err, iam.ErrUnknownIdentity),
		errors.Is(err, iam.ErrSubjectDisabled):
		// One message for all of them: no account enumeration.
		http.Error(w, "invalid credentials", http.StatusUnauthorized)
	case errors.Is(err, iam.ErrUnavailable):
		http.Error(w, "authentication temporarily unavailable", http.StatusServiceUnavailable)
	default:
		log.Printf("auth: login failed: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
	}
}

type refreshRequest struct {
	RefreshToken string `json:"refresh_token"`
}

// Refresh exchanges a refresh token for a new access + refresh pair. The
// presented refresh token is spent; presenting it again revokes the whole
// session (reuse detection).
func (h *Handler) Refresh(w http.ResponseWriter, r *http.Request) {
	var req refreshRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.RefreshToken == "" {
		http.Error(w, "refresh_token is required", http.StatusBadRequest)
		return
	}
	pair, err := h.auth.Service.Refresh(r.Context(), req.RefreshToken, h.auth.HTTP.ClientInfo(r))
	switch {
	case errors.Is(err, iam.ErrUnavailable):
		http.Error(w, "authentication temporarily unavailable", http.StatusServiceUnavailable)
		return
	case errors.Is(err, iam.ErrRefreshRaced):
		// A concurrent refresh with the same token already won; the
		// session is intact and the client should use that result.
		http.Error(w, "refresh token was just rotated", http.StatusConflict)
		return
	case err != nil:
		// Unknown, expired, revoked, reused, or the user is gone/disabled:
		// the client's only recourse is to log in again.
		http.Error(w, "invalid refresh token", http.StatusUnauthorized)
		return
	}
	h.writeTokens(w, http.StatusOK, pair.AccessToken, pair.RefreshToken)
}

// Logout ends the session the given refresh token belongs to. It doesn't
// require an access token, so a client whose access token already expired
// can still log out cleanly. Always 204 for well-formed requests.
func (h *Handler) Logout(w http.ResponseWriter, r *http.Request) {
	var req refreshRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.RefreshToken == "" {
		http.Error(w, "refresh_token is required", http.StatusBadRequest)
		return
	}
	if err := h.auth.Service.Logout(r.Context(), req.RefreshToken); err != nil {
		log.Printf("auth: logout failed: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// LogoutAll ends every session of the calling user, on every device.
// Refreshing stops at once; outstanding access tokens keep working until
// they expire unless VerifySessionOnAccess is on.
func (h *Handler) LogoutAll(w http.ResponseWriter, r *http.Request) {
	sub, ok := httpauth.SubjectFrom(r.Context())
	if !ok {
		http.Error(w, "unauthenticated", http.StatusUnauthorized)
		return
	}
	if _, err := h.auth.Service.RevokeAllSessions(r.Context(), sub.ID, ""); err != nil {
		log.Printf("auth: logout-all for subject %s failed: %v", sub.ID, err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// SessionResponse describes one of the caller's sessions (devices).
type SessionResponse struct {
	ID         string    `json:"id"`
	CreatedAt  time.Time `json:"created_at"`
	LastUsedAt time.Time `json:"last_used_at"`
	ExpiresAt  time.Time `json:"expires_at"`
	IP         string    `json:"ip,omitempty"`
	UserAgent  string    `json:"user_agent,omitempty"`
	// Current is true for the session the request's access token belongs to.
	Current bool `json:"current"`
}

// ListSessions returns the caller's active sessions.
func (h *Handler) ListSessions(w http.ResponseWriter, r *http.Request) {
	sub, ok := httpauth.SubjectFrom(r.Context())
	if !ok {
		http.Error(w, "unauthenticated", http.StatusUnauthorized)
		return
	}
	current := ""
	if s, ok := httpauth.SessionFrom(r.Context()); ok {
		current = s.ID
	}
	list, err := h.auth.Service.ListSessions(r.Context(), sub.ID)
	if err != nil {
		log.Printf("auth: list sessions for subject %s failed: %v", sub.ID, err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	out := make([]SessionResponse, 0, len(list))
	for _, s := range list {
		out = append(out, SessionResponse{
			ID: s.ID, CreatedAt: s.CreatedAt, LastUsedAt: s.LastUsedAt, ExpiresAt: s.ExpiresAt,
			IP: s.IP, UserAgent: s.UserAgent, Current: s.ID == current,
		})
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(out)
}

// RevokeSession ends one of the caller's sessions. Revoking a session that
// doesn't exist (or isn't theirs) is a 404.
func (h *Handler) RevokeSession(w http.ResponseWriter, r *http.Request) {
	sub, ok := httpauth.SubjectFrom(r.Context())
	if !ok {
		http.Error(w, "unauthenticated", http.StatusUnauthorized)
		return
	}
	id := mux.Vars(r)["id"]
	list, err := h.auth.Service.ListSessions(r.Context(), sub.ID)
	if err != nil {
		log.Printf("auth: list sessions for subject %s failed: %v", sub.ID, err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if !slices.ContainsFunc(list, func(s iam.SessionInfo) bool { return s.ID == id }) {
		http.Error(w, "session not found", http.StatusNotFound)
		return
	}
	if err := h.auth.Service.RevokeSession(r.Context(), sub.ID, id); err != nil {
		log.Printf("auth: revoke session for subject %s failed: %v", sub.ID, err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) writeTokens(w http.ResponseWriter, status int, access, refresh string) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(TokenResponse{
		AccessToken:  access,
		RefreshToken: refresh,
		TokenType:    "Bearer",
		ExpiresIn:    int(h.auth.accessTTL / time.Second),
	})
}

// MeResponse is the shape of /auth/me.
type MeResponse struct {
	ID    uint     `json:"id"`
	Email string   `json:"email"`
	Name  string   `json:"name"`
	Roles []string `json:"roles"`
	// IsModerator is read from the database, so it reflects a promotion
	// immediately — though moderator-only routes check the roles in the
	// access token, which catch up at the next refresh.
	IsModerator bool `json:"is_moderator"`
}

func (h *Handler) Me(w http.ResponseWriter, r *http.Request) {
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
	roles, err := h.auth.Users.roles(r.Context(), uid)
	if err != nil {
		log.Printf("auth: roles for user %d: %v", uid, err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if roles == nil {
		roles = []string{}
	}
	resp := MeResponse{
		ID: u.ID, Email: u.Email, Name: u.Name, Roles: roles,
		IsModerator: slices.Contains(roles, RoleModerator),
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(resp)
}

// validEmail is a deliberately minimal sanity check (non-empty, a single "@"
// with text on both sides, no spaces) — not RFC 5322. Real deliverability is
// proven by a confirmation email, which is out of scope here.
func validEmail(email string) bool {
	if email == "" || strings.ContainsAny(email, " \t\r\n") {
		return false
	}
	at := strings.IndexByte(email, '@')
	if at <= 0 || at != strings.LastIndexByte(email, '@') || at == len(email)-1 {
		return false
	}
	return strings.Contains(email[at+1:], ".")
}

func (h *Handler) HealthCheck(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}
	response := HealthResponse{
		Status: "Healthy",
		Time:   time.Now().Format(time.RFC3339),
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	if err := json.NewEncoder(w).Encode(response); err != nil {
		http.Error(w, "Internal Server Error", http.StatusInternalServerError)
	}
}
