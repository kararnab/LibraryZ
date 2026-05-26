package contribution

import (
	"context"
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

func (h *Handler) Submit(w http.ResponseWriter, r *http.Request) {
	workID, err := uuid.Parse(mux.Vars(r)["id"])
	if err != nil {
		http.Error(w, "invalid work id", http.StatusBadRequest)
		return
	}
	userID, ok := middleware.UserID(r.Context())
	if !ok {
		http.Error(w, "unauthenticated", http.StatusUnauthorized)
		return
	}
	var body struct {
		Patch map[string]any `json:"patch"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	c, err := h.service.Submit(r.Context(), workID, userID, body.Patch)
	switch {
	case errors.Is(err, ErrEmptyPatch):
		http.Error(w, "patch is required", http.StatusBadRequest)
		return
	case errors.Is(err, ErrWorkNotFound):
		http.Error(w, "work not found", http.StatusNotFound)
		return
	case err != nil:
		httpx.ServerError(w, r, "submit contribution", err)
		return
	}
	writeJSON(w, http.StatusCreated, c)
}

func (h *Handler) List(w http.ResponseWriter, r *http.Request) {
	cs, err := h.service.List(r.Context(), ListFilter{
		Status: Status(r.URL.Query().Get("status")),
		Limit:  parseIntDefault(r.URL.Query().Get("limit"), 50, 200),
		Offset: parseIntDefault(r.URL.Query().Get("offset"), 0, 0),
	})
	if err != nil {
		httpx.ServerError(w, r, "list contributions", err)
		return
	}
	writeJSON(w, http.StatusOK, cs)
}

// ListMine returns the calling user's own contributions. Separate route
// (/me/contributions) rather than a ?contributor=me pseudo-value on
// /contributions, so the public List handler stays trivially cacheable
// and auth is enforced by the router rather than re-parsed here.
func (h *Handler) ListMine(w http.ResponseWriter, r *http.Request) {
	userID, ok := middleware.UserID(r.Context())
	if !ok {
		http.Error(w, "unauthenticated", http.StatusUnauthorized)
		return
	}
	uid := userID
	cs, err := h.service.List(r.Context(), ListFilter{
		Status:        Status(r.URL.Query().Get("status")),
		ContributorID: &uid,
		Limit:         parseIntDefault(r.URL.Query().Get("limit"), 50, 200),
		Offset:        parseIntDefault(r.URL.Query().Get("offset"), 0, 0),
	})
	if err != nil {
		httpx.ServerError(w, r, "list own contributions", err)
		return
	}
	writeJSON(w, http.StatusOK, cs)
}

func (h *Handler) Get(w http.ResponseWriter, r *http.Request) {
	id, err := uuid.Parse(mux.Vars(r)["id"])
	if err != nil {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}
	c, err := h.service.Get(r.Context(), id)
	if errors.Is(err, ErrNotFound) {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	if err != nil {
		httpx.ServerError(w, r, "get contribution", err)
		return
	}
	writeJSON(w, http.StatusOK, c)
}

func (h *Handler) Approve(w http.ResponseWriter, r *http.Request) {
	h.decide(w, r, h.service.Approve)
}

func (h *Handler) Reject(w http.ResponseWriter, r *http.Request) {
	h.decide(w, r, h.service.Reject)
}

type decideFn func(context.Context, uuid.UUID, uint) (*Contribution, error)

func (h *Handler) decide(w http.ResponseWriter, r *http.Request, fn decideFn) {
	id, err := uuid.Parse(mux.Vars(r)["id"])
	if err != nil {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}
	userID, ok := middleware.UserID(r.Context())
	if !ok {
		http.Error(w, "unauthenticated", http.StatusUnauthorized)
		return
	}
	c, err := fn(r.Context(), id, userID)
	switch {
	case errors.Is(err, ErrNotFound):
		http.Error(w, "not found", http.StatusNotFound)
		return
	case errors.Is(err, ErrAlreadyDecided):
		http.Error(w, "contribution already decided", http.StatusConflict)
		return
	case err != nil:
		httpx.ServerError(w, r, "decide contribution", err)
		return
	}
	writeJSON(w, http.StatusOK, c)
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
