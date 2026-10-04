package catalog

import (
	"crypto/sha256"
	"encoding/hex"
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

// CreateWorkRequest is the client-settable subset of Work (matches the
// OpenAPI CreateWorkRequest schema). Decoding into this rather than Work
// keeps server-owned fields — id, timestamps, editions, tags — out of the
// client's reach; unknown JSON keys are ignored.
type CreateWorkRequest struct {
	Title           string `json:"title"`
	Subtitle        string `json:"subtitle"`
	Authors         string `json:"authors"`
	Description     string `json:"description"`
	Language        string `json:"language"`
	PublicationYear int    `json:"publication_year"`
	ISBN            string `json:"isbn"`
	OpenLibraryID   string `json:"openlibrary_id"`
}

func (h *Handler) CreateWork(w http.ResponseWriter, r *http.Request) {
	var req CreateWorkRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	if strings.TrimSpace(req.Title) == "" {
		http.Error(w, "title is required", http.StatusBadRequest)
		return
	}
	work := Work{
		Title:           strings.TrimSpace(req.Title),
		Subtitle:        req.Subtitle,
		Authors:         req.Authors,
		Description:     req.Description,
		Language:        req.Language,
		PublicationYear: req.PublicationYear,
		ISBN:            req.ISBN,
		OpenLibraryID:   req.OpenLibraryID,
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

	// Hash the upload as received, before sanitizing: that's what duplicate
	// detection keys on, since sanitized PDFs differ byte-wise every time.
	// sanitize.Validate seeks back to the start itself.
	h256 := sha256.New()
	if _, err := io.Copy(h256, file); err != nil {
		http.Error(w, "upload too large or malformed: "+err.Error(), http.StatusBadRequest)
		return
	}
	sourceSHA := hex.EncodeToString(h256.Sum(nil))

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
	clean, err := sanitize.SanitizeContext(r.Context(), format, file, header.Size)
	switch {
	case errors.Is(err, sanitize.ErrTooComplex):
		http.Error(w, err.Error(), http.StatusRequestEntityTooLarge)
		return
	case errors.Is(err, sanitize.ErrInvalidContent), errors.Is(err, sanitize.ErrActiveContent),
		errors.Is(err, sanitize.ErrFormatMismatch), errors.Is(err, sanitize.ErrUnsupportedFormat):
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	case err != nil:
		httpx.ServerError(w, r, "sanitize", err)
		return
	}
	defer clean.Close()

	ed, err := h.service.AddEdition(r.Context(), workID, format, language, userID, sourceSHA, clean)
	if errors.Is(err, ErrNotFound) {
		http.Error(w, "work not found", http.StatusNotFound)
		return
	}
	var dup *DuplicateEditionError
	if errors.As(err, &dup) {
		writeJSON(w, http.StatusConflict, DuplicateEditionResponse{
			Error:     "this file is already in the library",
			EditionID: dup.Existing.ID,
			WorkID:    dup.Existing.WorkID,
		})
		return
	}
	if err != nil {
		httpx.ServerError(w, r, "add edition", err)
		return
	}
	writeJSON(w, http.StatusCreated, ed)
}

// DuplicateEditionResponse is the 409 body for an upload whose content is
// already stored as an edition, naming that edition and its work.
type DuplicateEditionResponse struct {
	Error     string    `json:"error"`
	EditionID uuid.UUID `json:"edition_id"`
	WorkID    uuid.UUID `json:"work_id"`
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
