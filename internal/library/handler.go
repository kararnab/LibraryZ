package library

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"

	"github.com/google/uuid"
	"github.com/gorilla/mux"
	"github.com/kararnab/libraryZ/internal/httpx"
	"github.com/kararnab/libraryZ/internal/middleware"
)

type Handler struct {
	service *Service
}

func NewHandler(s *Service) *Handler { return &Handler{service: s} }

// List returns the calling user's library entries, newest-touched first.
// Optional ?status= and ?shelf= filters.
func (h *Handler) List(w http.ResponseWriter, r *http.Request) {
	userID, ok := middleware.UserID(r.Context())
	if !ok {
		http.Error(w, "unauthenticated", http.StatusUnauthorized)
		return
	}
	ubs, err := h.service.List(r.Context(), ListFilter{
		UserID: userID,
		Status: Status(r.URL.Query().Get("status")),
		Shelf:  r.URL.Query().Get("shelf"),
		Limit:  parseIntDefault(r.URL.Query().Get("limit"), 50, 200),
		Offset: parseIntDefault(r.URL.Query().Get("offset"), 0, 0),
	})
	if err != nil {
		httpx.ServerError(w, r, "list library", err)
		return
	}
	writeJSON(w, http.StatusOK, ubs)
}

func (h *Handler) Get(w http.ResponseWriter, r *http.Request) {
	userID, workID, ok := h.userAndWork(w, r)
	if !ok {
		return
	}
	ub, err := h.service.Get(r.Context(), userID, workID)
	switch {
	case errors.Is(err, ErrNotFound):
		http.Error(w, "not found", http.StatusNotFound)
		return
	case err != nil:
		httpx.ServerError(w, r, "get library entry", err)
		return
	}
	writeJSON(w, http.StatusOK, ub)
}

func (h *Handler) Upsert(w http.ResponseWriter, r *http.Request) {
	userID, workID, ok := h.userAndWork(w, r)
	if !ok {
		return
	}
	var req UpsertRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	ub, err := h.service.Upsert(r.Context(), userID, workID, req)
	switch {
	case errors.Is(err, ErrWorkNotFound):
		http.Error(w, "work not found", http.StatusNotFound)
		return
	case errors.Is(err, ErrInvalidStatus), errors.Is(err, ErrInvalidRating), errors.Is(err, ErrInvalidProgress):
		// These are sentinel validation errors with safe, user-facing text.
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	case err != nil:
		httpx.ServerError(w, r, "upsert library entry", err)
		return
	}
	writeJSON(w, http.StatusOK, ub)
}

func (h *Handler) Delete(w http.ResponseWriter, r *http.Request) {
	userID, workID, ok := h.userAndWork(w, r)
	if !ok {
		return
	}
	err := h.service.Delete(r.Context(), userID, workID)
	switch {
	case errors.Is(err, ErrNotFound):
		http.Error(w, "not found", http.StatusNotFound)
		return
	case err != nil:
		httpx.ServerError(w, r, "delete library entry", err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// userAndWork pulls the authenticated user id from context and the {id} path
// var (the work id) and parses it, writing the appropriate error response and
// returning ok=false on failure.
func (h *Handler) userAndWork(w http.ResponseWriter, r *http.Request) (uint, uuid.UUID, bool) {
	userID, ok := middleware.UserID(r.Context())
	if !ok {
		http.Error(w, "unauthenticated", http.StatusUnauthorized)
		return 0, uuid.Nil, false
	}
	workID, err := uuid.Parse(mux.Vars(r)["id"])
	if err != nil {
		http.Error(w, "invalid work id", http.StatusBadRequest)
		return 0, uuid.Nil, false
	}
	return userID, workID, true
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func parseIntDefault(s string, def, max int) int {
	if s == "" {
		return def
	}
	n, err := strconv.Atoi(s)
	if err != nil || n < 0 {
		return def
	}
	if max > 0 && n > max {
		return max
	}
	return n
}
