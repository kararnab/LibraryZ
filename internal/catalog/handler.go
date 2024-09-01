package catalog

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"

	"github.com/google/uuid"
	"github.com/gorilla/mux"
	"github.com/kararnab/libraryZ/internal/middleware"
)

type Handler struct {
	service       *Service
	maxUploadSize int64
}

func NewHandler(service *Service, maxUploadSize int64) *Handler {
	return &Handler{service: service, maxUploadSize: maxUploadSize}
}

func (h *Handler) ListWorks(w http.ResponseWriter, r *http.Request) {
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	works, err := h.service.ListWorks(r.Context(), limit, offset)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, works)
}

func (h *Handler) GetWork(w http.ResponseWriter, r *http.Request) {
	id, err := uuid.Parse(mux.Vars(r)["id"])
	if err != nil {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}
	work, err := h.service.GetWork(r.Context(), id)
	if errors.Is(err, ErrNotFound) {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, work)
}

func (h *Handler) CreateWork(w http.ResponseWriter, r *http.Request) {
	var work Work
	if err := json.NewDecoder(r.Body).Decode(&work); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	if work.Title == "" {
		http.Error(w, "title is required", http.StatusBadRequest)
		return
	}
	if err := h.service.CreateWork(r.Context(), &work); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusCreated, work)
}

// UploadEdition accepts multipart/form-data: fields `format`, optional
// `language`, and file field `file`.
func (h *Handler) UploadEdition(w http.ResponseWriter, r *http.Request) {
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

	r.Body = http.MaxBytesReader(w, r.Body, h.maxUploadSize)
	if err := r.ParseMultipartForm(32 << 20); err != nil {
		http.Error(w, "upload too large or malformed: "+err.Error(), http.StatusBadRequest)
		return
	}

	format := r.FormValue("format")
	if format == "" {
		http.Error(w, "format is required", http.StatusBadRequest)
		return
	}
	language := r.FormValue("language")

	file, header, err := r.FormFile("file")
	if err != nil {
		http.Error(w, "file is required", http.StatusBadRequest)
		return
	}
	defer file.Close()
	_ = header

	ed, err := h.service.AddEdition(r.Context(), workID, format, language, userID, file)
	if errors.Is(err, ErrNotFound) {
		http.Error(w, "work not found", http.StatusNotFound)
		return
	}
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusCreated, ed)
}

func (h *Handler) GetEdition(w http.ResponseWriter, r *http.Request) {
	id, err := uuid.Parse(mux.Vars(r)["id"])
	if err != nil {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}
	ed, err := h.service.GetEdition(r.Context(), id)
	if errors.Is(err, ErrNotFound) {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, ed)
}

func (h *Handler) DownloadEdition(w http.ResponseWriter, r *http.Request) {
	id, err := uuid.Parse(mux.Vars(r)["id"])
	if err != nil {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}
	ed, rc, err := h.service.OpenEdition(r.Context(), id)
	if errors.Is(err, ErrNotFound) {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	defer rc.Close()

	filename := fmt.Sprintf("%s.%s", ed.ID, ed.Format)
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.FormatInt(ed.SizeBytes, 10))
	w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=%q", filename))
	w.Header().Set("X-Content-SHA256", ed.SHA256)
	_, _ = io.Copy(w, rc)
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
