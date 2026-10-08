package catalog

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode"

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
	if errors.Is(err, ErrRemoved) {
		http.Error(w, err.Error(), http.StatusConflict)
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
	dl, err := h.service.OpenEdition(r.Context(), id)
	if errors.Is(err, ErrNotFound) {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	if err != nil {
		httpx.ServerError(w, r, "open edition", err)
		return
	}
	defer dl.Body.Close()
	ed, rc, size := dl.Edition, dl.Body, dl.Size

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

	w.Header().Set("Content-Type", "application/octet-stream")
	if size >= 0 {
		w.Header().Set("Content-Length", strconv.FormatInt(size, 10))
	}
	w.Header().Set("Content-Disposition", contentDisposition(downloadFilename(dl.WorkTitle, dl.WorkAuthors, ed)))
	w.Header().Set("X-Content-SHA256", ed.SHA256)
	if _, err := io.Copy(w, rc); err != nil {
		log.Printf("download edition %s: streaming aborted after headers sent: %v", ed.ID, err)
	}
}

// DeleteWork is the moderator takedown for a work and all its editions.
// Body: {"reason": "..."} (required).
func (h *Handler) DeleteWork(w http.ResponseWriter, r *http.Request) {
	h.remove(w, r, h.service.DeleteWork)
}

// DeleteEdition is the moderator takedown for one edition.
// Body: {"reason": "..."} (required).
func (h *Handler) DeleteEdition(w http.ResponseWriter, r *http.Request) {
	h.remove(w, r, h.service.DeleteEdition)
}

func (h *Handler) remove(w http.ResponseWriter, r *http.Request, fn func(context.Context, uuid.UUID, uint, string) error) {
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
	var body struct {
		Reason string `json:"reason"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 64<<10)).Decode(&body); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	switch err := fn(r.Context(), id, userID, body.Reason); {
	case errors.Is(err, ErrReasonRequired):
		http.Error(w, err.Error(), http.StatusBadRequest)
	case errors.Is(err, ErrNotFound):
		http.Error(w, "not found", http.StatusNotFound)
	case err != nil:
		httpx.ServerError(w, r, "remove", err)
	default:
		w.WriteHeader(http.StatusNoContent)
	}
}

// maxFilenameStem caps the "<Title> - <Authors>" part of a download name (in
// runes), well under common 255-byte filesystem limits once UTF-8 encoded.
const maxFilenameStem = 120

// downloadFilename builds "<Title> - <Authors>.<format>" for a download,
// falling back to the edition id when the work has no usable title.
func downloadFilename(title, authors string, ed *Edition) string {
	stem := cleanFilenamePart(title)
	if a := cleanFilenamePart(authors); stem != "" && a != "" {
		stem += " - " + a
	}
	if stem == "" {
		stem = ed.ID.String()
	}
	if r := []rune(stem); len(r) > maxFilenameStem {
		stem = strings.TrimSpace(string(r[:maxFilenameStem]))
	}
	return stem + "." + cleanFilenamePart(strings.ToLower(ed.Format))
}

// cleanFilenamePart drops path separators, control characters, and
// characters Windows forbids, collapses whitespace, and strips leading dots
// so the result can't escape a directory or become a hidden file.
func cleanFilenamePart(s string) string {
	var b strings.Builder
	for _, r := range s {
		switch {
		case unicode.IsControl(r), strings.ContainsRune(`/\:*?"<>|`, r):
			b.WriteRune(' ')
		default:
			b.WriteRune(r)
		}
	}
	words := strings.Fields(b.String())
	kept := words[:0]
	for _, w := range words {
		if strings.Trim(w, ".") != "" { // drop "." / ".." path segments
			kept = append(kept, w)
		}
	}
	return strings.TrimSpace(strings.TrimLeft(strings.Join(kept, " "), "."))
}

// contentDisposition renders an RFC 6266 attachment header: an ASCII-only
// `filename` for old clients plus a UTF-8 `filename*` (RFC 8187) that modern
// clients prefer, so non-ASCII titles survive.
func contentDisposition(name string) string {
	var ascii strings.Builder
	for _, r := range name {
		if r < 0x20 || r > 0x7e || r == '"' || r == '\\' {
			ascii.WriteByte('_')
		} else {
			ascii.WriteRune(r)
		}
	}
	return fmt.Sprintf(`attachment; filename="%s"; filename*=UTF-8''%s`, ascii.String(), encodeRFC8187(name))
}

// encodeRFC8187 percent-encodes every byte outside RFC 8187's attr-char set.
func encodeRFC8187(s string) string {
	const attrChars = "!#$&+-.^_`|~"
	var b strings.Builder
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c < 0x80 && (unicode.IsLetter(rune(c)) || unicode.IsDigit(rune(c)) || strings.IndexByte(attrChars, c) >= 0) {
			b.WriteByte(c)
		} else {
			fmt.Fprintf(&b, "%%%02X", c)
		}
	}
	return b.String()
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
