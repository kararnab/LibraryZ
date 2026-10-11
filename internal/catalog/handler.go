package catalog

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"maps"
	"math"
	"net/http"
	"slices"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

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
	if utf8.RuneCountInString(q) > MaxQueryRunes {
		http.Error(w, fmt.Sprintf("q must be at most %d characters", MaxQueryRunes), http.StatusBadRequest)
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

// validate holds the fields to the limits a suggested edit has
// (CheckWorkField). A zero publication_year means "not given".
func (r CreateWorkRequest) validate() error {
	fields := map[string]any{
		"title": r.Title, "subtitle": r.Subtitle, "authors": r.Authors, "description": r.Description,
		"language": r.Language, "isbn": r.ISBN, "openlibrary_id": r.OpenLibraryID,
	}
	if r.PublicationYear != 0 {
		fields["publication_year"] = r.PublicationYear
	}
	for _, k := range slices.Sorted(maps.Keys(fields)) {
		if err := CheckWorkField(k, fields[k]); err != nil {
			return err
		}
	}
	return nil
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
	if err := req.validate(); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
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
	if uid, ok := middleware.UserID(r.Context()); ok {
		work.CreatedByUserID = &uid
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

	// Refuse before reading the body when the quota is already used up, so
	// a client that didn't check GET /me/upload-quota first wastes as
	// little as possible. ContentLength includes the multipart framing
	// (boundaries, part headers, the form fields), so take an allowance for
	// it off: otherwise a file GET /me/upload-quota said fits could be
	// refused here. AddEdition makes the exact check with the file's size.
	if err := h.service.CheckUploadQuota(r.Context(), userID, max(r.ContentLength-multipartOverhead, 0)); err != nil {
		if !writeQuotaExceeded(w, err) {
			httpx.ServerError(w, r, "upload quota", err)
		}
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
	if err := CheckWorkField("language", language); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

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

	ed, err := h.service.AddEdition(r.Context(), workID, format, language, userID, sourceSHA, header.Size, clean)
	if errors.Is(err, ErrNotFound) {
		http.Error(w, "work not found", http.StatusNotFound)
		return
	}
	if writeQuotaExceeded(w, err) {
		return
	}
	if errors.Is(err, ErrRemoved) {
		// Same 409 shape as a duplicate, minus the edition/work to link to.
		writeJSON(w, http.StatusConflict, map[string]string{"error": err.Error()})
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

// multipartOverhead is what an upload's request may carry besides the file
// itself: boundaries, part headers (a long filename included) and the
// format/language fields come to well under this.
const multipartOverhead = 16 << 10

// UploadQuotaResponse is GET /me/upload-quota's body: the status, plus
// whether a file of the requested size fits now and, if not, when it will.
type UploadQuotaResponse struct {
	QuotaStatus
	Fits              bool  `json:"fits"`
	RetryAfterSeconds int64 `json:"retry_after_seconds"`
}

// UploadQuota is GET /me/upload-quota[?size=N]: the caller's standing
// against their upload quota, so clients can check a file before a large
// upload starts. size defaults to 0 (is there room for another file?).
func (h *Handler) UploadQuota(w http.ResponseWriter, r *http.Request) {
	userID, ok := middleware.UserID(r.Context())
	if !ok {
		http.Error(w, "unauthenticated", http.StatusUnauthorized)
		return
	}
	var size int64
	if v := r.URL.Query().Get("size"); v != "" {
		n, err := strconv.ParseInt(v, 10, 64)
		if err != nil || n < 0 {
			http.Error(w, "size must be a non-negative integer", http.StatusBadRequest)
			return
		}
		size = n
	}
	st, err := h.service.UploadQuotaStatus(r.Context(), userID)
	if err != nil {
		httpx.ServerError(w, r, "upload quota", err)
		return
	}
	resp := UploadQuotaResponse{QuotaStatus: st, Fits: true}
	var q *QuotaExceededError
	if errors.As(st.Admit(size), &q) {
		resp.Fits = false
		resp.RetryAfterSeconds = retryAfterSeconds(q.RetryAfter)
	}
	writeJSON(w, http.StatusOK, resp)
}

func retryAfterSeconds(d time.Duration) int64 {
	return max(int64(math.Ceil(d.Seconds())), 1)
}

// writeQuotaExceeded writes the 429 for a *QuotaExceededError, with
// Retry-After in whole seconds, and reports whether err was one.
func writeQuotaExceeded(w http.ResponseWriter, err error) bool {
	var q *QuotaExceededError
	if !errors.As(err, &q) {
		return false
	}
	w.Header().Set("Retry-After", strconv.FormatInt(retryAfterSeconds(q.RetryAfter), 10))
	http.Error(w, q.Error(), http.StatusTooManyRequests)
	return true
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

// DeleteWork removes a work and all its editions: a moderator takedown, or
// a creator discarding their own still-empty work. Body: {"reason": "...",
// "kind": "content" | "technical"} (reason required; kind defaults to
// content).
func (h *Handler) DeleteWork(w http.ResponseWriter, r *http.Request) {
	h.remove(w, r, h.service.DeleteWork)
}

// DeleteEdition is the moderator takedown for one edition. Body as for
// DeleteWork; "technical" (a broken file) lets the same file be uploaded
// again.
func (h *Handler) DeleteEdition(w http.ResponseWriter, r *http.Request) {
	h.remove(w, r, h.service.DeleteEdition)
}

func (h *Handler) remove(w http.ResponseWriter, r *http.Request, fn func(context.Context, uuid.UUID, uint, string, RemovalKind) error) {
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
		Reason string      `json:"reason"`
		Kind   RemovalKind `json:"kind"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 64<<10)).Decode(&body); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	switch err := fn(r.Context(), id, userID, body.Reason, body.Kind); {
	case errors.Is(err, ErrReasonRequired), errors.Is(err, ErrBadRemovalKind):
		http.Error(w, err.Error(), http.StatusBadRequest)
	case errors.Is(err, ErrForbidden):
		http.Error(w, err.Error(), http.StatusForbidden)
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
