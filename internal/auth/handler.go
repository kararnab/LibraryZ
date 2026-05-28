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

	token, err := h.service.Authenticate(user.Email, user.Password)
	if errors.Is(err, ErrInvalidCredentials) {
		http.Error(w, "invalid credentials", http.StatusUnauthorized)
		return
	}
	if err != nil {
		log.Printf("auth: login for %q failed: %v", user.Email, err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Authorization", "Bearer "+token)
	w.WriteHeader(http.StatusOK)
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
