package auth

import (
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"time"

	"github.com/kararnab/libraryZ/internal/middleware"
)

type HealthResponse struct {
	Status string `json:"status"`
	Time   string `json:"time"`
}

type Handler struct {
	service *Service
}

func NewHandler(service *Service) *Handler {
	return &Handler{service: service}
}

func (h *Handler) SignUp(w http.ResponseWriter, r *http.Request) {
	var user User
	if err := json.NewDecoder(r.Body).Decode(&user); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	err := h.service.CreateUser(user)
	switch {
	case errors.Is(err, ErrInvalidEmail), errors.Is(err, ErrWeakPassword):
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	case errors.Is(err, ErrEmailExists):
		http.Error(w, err.Error(), http.StatusConflict)
		return
	case err != nil:
		log.Printf("auth: signup failed: %v", err)
		http.Error(w, "internal server error", http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusCreated)
}

func (h *Handler) Login(w http.ResponseWriter, r *http.Request) {
	var user User
	if err := json.NewDecoder(r.Body).Decode(&user); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	pair, err := h.service.Authenticate(user.Email, user.Password)
	if errors.Is(err, ErrInvalidCredentials) {
		http.Error(w, "invalid credentials", http.StatusUnauthorized)
		return
	}
	if err != nil {
		log.Printf("auth: login for %q failed: %v", user.Email, err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	writeTokens(w, pair)
}

type refreshRequest struct {
	RefreshToken string `json:"refresh_token"`
}

// Refresh exchanges a refresh token for a new access + refresh pair. The
// presented refresh token is spent (see Service.Refresh).
func (h *Handler) Refresh(w http.ResponseWriter, r *http.Request) {
	var req refreshRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.RefreshToken == "" {
		http.Error(w, "refresh_token is required", http.StatusBadRequest)
		return
	}
	pair, err := h.service.Refresh(req.RefreshToken)
	if errors.Is(err, ErrInvalidRefreshToken) {
		http.Error(w, err.Error(), http.StatusUnauthorized)
		return
	}
	if err != nil {
		log.Printf("auth: refresh failed: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	writeTokens(w, pair)
}

// Logout revokes the session the given refresh token belongs to. It doesn't
// require an access token, so a client whose access token already expired
// can still log out cleanly. Always 204.
func (h *Handler) Logout(w http.ResponseWriter, r *http.Request) {
	var req refreshRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.RefreshToken == "" {
		http.Error(w, "refresh_token is required", http.StatusBadRequest)
		return
	}
	if err := h.service.Logout(req.RefreshToken); err != nil {
		log.Printf("auth: logout failed: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// LogoutAll ends every session of the calling user, on every device.
func (h *Handler) LogoutAll(w http.ResponseWriter, r *http.Request) {
	uid, ok := middleware.UserID(r.Context())
	if !ok {
		http.Error(w, "unauthenticated", http.StatusUnauthorized)
		return
	}
	if err := h.service.LogoutAll(uid); err != nil {
		log.Printf("auth: logout-all for user %d failed: %v", uid, err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// writeTokens sends the pair as JSON. The access token is also mirrored in
// the Authorization response header, which is how clients predating refresh
// tokens read it.
func writeTokens(w http.ResponseWriter, pair *TokenPair) {
	w.Header().Set("Authorization", "Bearer "+pair.AccessToken)
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(pair)
}

// MeResponse is the shape of /auth/me. Defined separately so we can
// expose IsModerator (which is `json:"-"` on the User model to block
// signup-time privilege escalation) without leaking the password hash.
type MeResponse struct {
	ID          uint   `json:"id"`
	Email       string `json:"email"`
	Name        string `json:"name"`
	IsModerator bool   `json:"is_moderator"`
}

func (h *Handler) Me(w http.ResponseWriter, r *http.Request) {
	uid, ok := middleware.UserID(r.Context())
	if !ok {
		http.Error(w, "unauthenticated", http.StatusUnauthorized)
		return
	}
	u, err := h.service.GetByID(uid)
	if err != nil {
		http.Error(w, "user not found", http.StatusNotFound)
		return
	}
	resp := MeResponse{ID: u.ID, Email: u.Email, Name: u.Name, IsModerator: u.IsModerator}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(resp)
}

func (h *Handler) HealthCheck(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}

	// Get the current time
	currentTime := time.Now().Format(time.RFC3339)

	// Prepare response
	response := HealthResponse{
		Status: "Healthy",
		Time:   currentTime,
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	if err := json.NewEncoder(w).Encode(response); err != nil {
		http.Error(w, "Internal Server Error", http.StatusInternalServerError)
	}
}
