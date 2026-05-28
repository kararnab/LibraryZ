package catalog

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/gorilla/mux"
	"github.com/kararnab/libraryZ/internal/httpx"
	"github.com/kararnab/libraryZ/internal/middleware"
	"github.com/kararnab/libraryZ/internal/sanitize"
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
		httpx.ServerError(w, r, "list works", err)
		return
	}
	writeJSON(w, http.StatusOK, works)
}

func (h *Handler) SearchWorks(w http.ResponseWriter, r *http.Request) {
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	if q == "" {
		http.Error(w, "q is required", http.StatusBadRequest)
		return
	}
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	works, err := h.service.SearchWorks(r.Context(), q, limit, offset)
	if err != nil {
		httpx.ServerError(w, r, "search works", err)
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
		httpx.ServerError(w, r, "get work", err)
		return
	}
	writeJSON(w, http.StatusOK, work)
}

func (h *Handler) CreateWork(w http.ResponseWriter, r *http.Request) {
	var work Work
	if err := json.NewDecoder(r.Body).Decode(&work); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	if work.Title == "" {
		http.Error(w, "title is required", http.StatusBadRequest)
		return
	}
	if err := h.service.CreateWork(r.Context(), &work); err != nil {
		httpx.ServerError(w, r, "create work", err)
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

	// A large upload over a slow link can legitimately exceed the server's
	// global ReadTimeout. Clear the read deadline for this handler so the
	// timeout protects normal requests without truncating big uploads — abuse
	// is still bounded by auth + the MaxBytesReader size cap below.
	// (Best-effort: SetReadDeadline errors only if the conn doesn't support it.)
	_ = http.NewResponseController(w).SetReadDeadline(time.Time{})

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

	// L1 sanitize: declared format must match the bytes' magic, EPUB must not
	// be a zip bomb, TXT must be valid UTF-8. Anything off the allowlist
	// (currently PDF/EPUB/TXT) is rejected at this layer. multipart.File is
	// a ReadSeeker so EPUB's zip-central-directory probe works without
	// buffering the upload.
	if err := sanitize.Validate(format, file, header.Size); err != nil {
		switch {
		case errors.Is(err, sanitize.ErrUnsupportedFormat):
			http.Error(w, err.Error(), http.StatusUnsupportedMediaType)
		default:
			http.Error(w, err.Error(), http.StatusBadRequest)
		}
		return
	}

	// L2 sanitize: PDFs are re-serialized with active content (JavaScript,
	// embedded files, XFA, etc.) stripped; EPUBs containing any script,
	// event handler, or javascript: URL are rejected; TXTs pass through.
	// The returned reader is what we store — for PDFs its bytes (and sha)
	// differ from the upload, which is intentional (canonical safe blob).
	clean, err := sanitize.Sanitize(format, file, header.Size)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	ed, err := h.service.AddEdition(r.Context(), workID, format, language, userID, clean)
	if errors.Is(err, ErrNotFound) {
		http.Error(w, "work not found", http.StatusNotFound)
		return
	}
	if err != nil {
		httpx.ServerError(w, r, "add edition", err)
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
		httpx.ServerError(w, r, "get edition", err)
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
	ed, rc, size, err := h.service.OpenEdition(r.Context(), id)
	if errors.Is(err, ErrNotFound) {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	if err != nil {
		httpx.ServerError(w, r, "open edition", err)
		return
	}
	defer rc.Close()

	// Editions can be up to 500 MiB; streaming one over a slow link can exceed
	// the server WriteTimeout. Clear the write deadline for this handler so the
	// global timeout still guards normal responses without cutting downloads.
	_ = http.NewResponseController(w).SetWriteDeadline(time.Time{})

	// Frame the response with the size the storage backend actually reports,
	// not ed.SizeBytes — if the stored object has drifted from the recorded
	// size, trusting the DB value would declare a Content-Length we can't meet
	// and truncate or hang the client. A drift is worth surfacing.
	if size != ed.SizeBytes {
		log.Printf("download edition %s: storage size %d != recorded size_bytes %d", ed.ID, size, ed.SizeBytes)
	}

	filename := fmt.Sprintf("%s.%s", ed.ID, ed.Format)
	w.Header().Set("Content-Type", "application/octet-stream")
	if size >= 0 {
		w.Header().Set("Content-Length", strconv.FormatInt(size, 10))
	}
	w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=%q", filename))
	w.Header().Set("X-Content-SHA256", ed.SHA256)
	if _, err := io.Copy(w, rc); err != nil {
		log.Printf("download edition %s: streaming aborted after headers sent: %v", ed.ID, err)
	}
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
