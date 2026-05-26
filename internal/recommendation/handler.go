package recommendation

import (
	"encoding/json"
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

// Recommend returns the calling user's personalized work suggestions.
func (h *Handler) Recommend(w http.ResponseWriter, r *http.Request) {
	userID, ok := middleware.UserID(r.Context())
	if !ok {
		http.Error(w, "unauthenticated", http.StatusUnauthorized)
		return
	}
	recs, err := h.service.Recommend(
		r.Context(),
		userID,
		parseIntDefault(r.URL.Query().Get("limit"), 20, 100),
	)
	if err != nil {
		httpx.ServerError(w, r, "recommend", err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(recs)
}

// Dismiss hides the work named in the path from the caller's future
// recommendations. {id} is the work id.
func (h *Handler) Dismiss(w http.ResponseWriter, r *http.Request) {
	userID, ok := middleware.UserID(r.Context())
	if !ok {
		http.Error(w, "unauthenticated", http.StatusUnauthorized)
		return
	}
	workID, err := uuid.Parse(mux.Vars(r)["id"])
	if err != nil {
		http.Error(w, "invalid work id", http.StatusBadRequest)
		return
	}
	if err := h.service.Dismiss(r.Context(), userID, workID); err != nil {
		httpx.ServerError(w, r, "dismiss recommendation", err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
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
