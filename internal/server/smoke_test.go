package server_test

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"github.com/glebarez/sqlite"
	"github.com/google/uuid"
	"github.com/kararnab/libraryZ/internal/recommendation"
	"github.com/kararnab/libraryZ/internal/server"
	"github.com/kararnab/libraryZ/internal/storage"
	"golang.org/x/crypto/bcrypt"
	"gorm.io/gorm"
)

// newTestDeps builds the DB + storage backing a test server. Callers set the
// CORS fields they want before passing it to server.New.
func newTestDeps(t *testing.T) (server.Deps, *gorm.DB) {
	t.Helper()

	db, err := gorm.Open(sqlite.Open("file::memory:?cache=shared&_pragma=foreign_keys(1)"), &gorm.Config{})
	if err != nil {
		t.Fatalf("open sqlite: %v", err)
	}
	if err := server.Migrate(db); err != nil {
		t.Fatalf("migrate: %v", err)
	}

	store, err := storage.NewLocal(filepath.Join(t.TempDir(), "blobs"))
	if err != nil {
		t.Fatalf("storage: %v", err)
	}

	return server.Deps{
		DB: db, Storage: store, MaxUploadBytes: 16 << 20,
		JWTSecret: testJWTSecret,
		// The production default (cmd/libraryz): roles are read per request.
		LoadSubjectOnAccess: true,
	}, db
}

// testJWTSecret is long enough for HS256 (32 bytes).
const (
	testJWTSecret = "test-secret-test-secret-test-secret!"
	testPassword  = "hunter2hunter2"
)

func newServer(t *testing.T, deps server.Deps) *httptest.Server {
	t.Helper()
	h, err := server.New(deps)
	if err != nil {
		t.Fatalf("server.New: %v", err)
	}
	ts := httptest.NewServer(h)
	t.Cleanup(ts.Close)
	return ts
}

func newTestServer(t *testing.T) (*httptest.Server, *gorm.DB) {
	t.Helper()
	deps, db := newTestDeps(t)
	// Exercise allowlist mode explicitly; the CORS tests use this origin.
	deps.AllowedOrigins = []string{"http://example.test"}
	return newServer(t, deps), db
}

// grantModerator gives the user the "moderator" role, the v0 production
// path ("insert via psql").
func grantModerator(t *testing.T, db *gorm.DB, email string) {
	t.Helper()
	res := db.Exec(`INSERT INTO user_roles (user_id, role) SELECT id, 'moderator' FROM users WHERE email = ?`, email)
	if res.Error != nil {
		t.Fatalf("promote %s: %v", email, res.Error)
	}
	if res.RowsAffected != 1 {
		t.Fatalf("promote %s: expected 1 row affected, got %d", email, res.RowsAffected)
	}
}

func TestSmokeHappyPath(t *testing.T) {
	ts, _ := newTestServer(t)

	// Health
	if resp, err := http.Get(ts.URL + "/health"); err != nil || resp.StatusCode != 200 {
		t.Fatalf("health: err=%v code=%d", err, statusOf(resp))
	}

	// Signup
	signup := mustJSON(t, map[string]string{"email": "a@b.com", "password": testPassword, "name": "Test"})
	if resp, err := http.Post(ts.URL+"/auth/signup", "application/json", signup); err != nil || resp.StatusCode != 201 {
		t.Fatalf("signup: err=%v code=%d", err, statusOf(resp))
	}

	// Login -> token pair in the body
	auth := "Bearer " + loginPair(t, ts.URL, "a@b.com", testPassword).AccessToken

	// Create work (authenticated)
	createBody := mustJSON(t, map[string]any{
		"title":   "Moby-Dick",
		"authors": "Herman Melville",
	})
	req, _ := http.NewRequest(http.MethodPost, ts.URL+"/works", createBody)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", auth)
	createResp, err := http.DefaultClient.Do(req)
	if err != nil || createResp.StatusCode != 201 {
		t.Fatalf("create work: err=%v code=%d body=%s", err, statusOf(createResp), readBody(createResp))
	}
	var work struct {
		ID    string `json:"id"`
		Title string `json:"title"`
	}
	if err := json.NewDecoder(createResp.Body).Decode(&work); err != nil {
		t.Fatalf("decode work: %v", err)
	}
	if work.ID == "" || work.Title != "Moby-Dick" {
		t.Fatalf("unexpected work: %+v", work)
	}

	// Upload edition (multipart). TXT is the passthrough format under
	// internal/sanitize — bytes round-trip exactly, so the byte-equality
	// download assertion below stays meaningful. PDF round-trip differs
	// because sanitize.Sanitize re-serializes; that path is covered by
	// the sanitize package tests directly.
	fileBytes := []byte("Call me Ishmael. Some years ago — never mind how long precisely…\n")
	editionResp := uploadEdition(t, ts.URL, auth, work.ID, "txt", "en", fileBytes)
	if editionResp.StatusCode != 201 {
		t.Fatalf("upload edition: code=%d body=%s", editionResp.StatusCode, readBody(editionResp))
	}
	var ed struct {
		ID        string `json:"id"`
		SHA256    string `json:"sha256"`
		SizeBytes int64  `json:"size_bytes"`
	}
	if err := json.NewDecoder(editionResp.Body).Decode(&ed); err != nil {
		t.Fatalf("decode edition: %v", err)
	}
	if ed.SizeBytes != int64(len(fileBytes)) || ed.SHA256 == "" {
		t.Fatalf("bad edition meta: %+v", ed)
	}

	// Duplicate: re-upload identical bytes -> 409 naming the existing edition
	dupResp := uploadEdition(t, ts.URL, auth, work.ID, "txt", "en", fileBytes)
	if dupResp.StatusCode != http.StatusConflict {
		t.Fatalf("duplicate upload: want 409, got %d body=%s", dupResp.StatusCode, readBody(dupResp))
	}
	var dup struct {
		EditionID string `json:"edition_id"`
		WorkID    string `json:"work_id"`
	}
	_ = json.NewDecoder(dupResp.Body).Decode(&dup)
	if dup.EditionID != ed.ID || dup.WorkID != work.ID {
		t.Fatalf("duplicate upload: want edition %s on work %s, got %+v", ed.ID, work.ID, dup)
	}

	// GET edition metadata
	getEdResp, err := http.Get(ts.URL + "/editions/" + ed.ID)
	if err != nil || getEdResp.StatusCode != 200 {
		t.Fatalf("get edition: err=%v code=%d", err, statusOf(getEdResp))
	}

	// Download edition bytes
	dlResp, err := http.Get(ts.URL + "/editions/" + ed.ID + "/download")
	if err != nil || dlResp.StatusCode != 200 {
		t.Fatalf("download: err=%v code=%d", err, statusOf(dlResp))
	}
	got, _ := io.ReadAll(dlResp.Body)
	if !bytes.Equal(got, fileBytes) {
		t.Fatalf("download: bytes mismatch (got %d want %d)", len(got), len(fileBytes))
	}
	if dlResp.Header.Get("X-Content-SHA256") != ed.SHA256 {
		t.Fatalf("download: sha header mismatch")
	}
	if cd := dlResp.Header.Get("Content-Disposition"); !strings.Contains(cd, `filename="Moby-Dick - Herman Melville.txt"`) {
		t.Fatalf("download: Content-Disposition should name the work, got %q", cd)
	}

	// GET work shows the edition
	getWorkResp, _ := http.Get(ts.URL + "/works/" + work.ID)
	var withEd struct {
		Editions []struct{ ID string } `json:"editions"`
	}
	_ = json.NewDecoder(getWorkResp.Body).Decode(&withEd)
	if len(withEd.Editions) != 1 || withEd.Editions[0].ID != ed.ID {
		t.Fatalf("get work: editions missing or wrong: %+v", withEd)
	}
}

func TestReadyReportsDependencies(t *testing.T) {
	ts, _ := newTestServer(t)
	resp, err := http.Get(ts.URL + "/ready")
	if err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("ready: err=%v code=%d body=%s", err, statusOf(resp), readBody(resp))
	}
	var body struct {
		Status string            `json:"status"`
		Checks map[string]string `json:"checks"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if body.Status != "ready" || body.Checks["database"] != "ok" || body.Checks["storage"] != "ok" {
		t.Fatalf("unexpected body: %+v", body)
	}
}

// failingStore is a Storage whose backend is unreachable.
type failingStore struct{ storage.Storage }

func (failingStore) Exists(context.Context, string) (bool, error) {
	return false, errors.New("dial tcp: connection refused")
}

func TestReadyReturns503WhenADependencyIsDown(t *testing.T) {
	deps, db := newTestDeps(t)
	deps.Storage = failingStore{deps.Storage}
	ts := newServer(t, deps)

	resp, _ := http.Get(ts.URL + "/ready")
	body := readBody(resp)
	if resp.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("storage down: want 503, got %d %s", resp.StatusCode, body)
	}
	if !strings.Contains(body, `"storage":"unavailable"`) || !strings.Contains(body, `"database":"ok"`) {
		t.Fatalf("storage down: body = %s", body)
	}
	if strings.Contains(body, "connection refused") {
		t.Fatalf("error details leaked: %s", body)
	}
	// Liveness is unaffected.
	if r, _ := http.Get(ts.URL + "/health"); r.StatusCode != http.StatusOK {
		t.Fatalf("health should stay 200, got %d", r.StatusCode)
	}

	// Database down too.
	sqlDB, _ := db.DB()
	sqlDB.Close()
	resp, _ = http.Get(ts.URL + "/ready")
	if body := readBody(resp); resp.StatusCode != http.StatusServiceUnavailable || !strings.Contains(body, `"database":"unavailable"`) {
		t.Fatalf("db down: got %d %s", resp.StatusCode, body)
	}
}

// signupLogin creates a user and returns its "Bearer …" Authorization value.
func signupLogin(t *testing.T, base, email string) string {
	t.Helper()
	return signupAndLogin(t, base, email, testPassword, "T")
}

// postWork creates a work from an arbitrary JSON body and returns the
// response, decoded into a map.
func postWork(t *testing.T, base, auth string, body map[string]any) (int, map[string]any) {
	t.Helper()
	req, _ := http.NewRequest(http.MethodPost, base+"/works", mustJSON(t, body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", auth)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("create work: %v", err)
	}
	var out map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&out)
	return resp.StatusCode, out
}

// Server-owned fields in a create-work body (id, editions, tags, timestamps)
// must be ignored: editions only come from real uploads.
func TestCreateWorkIgnoresServerOwnedFields(t *testing.T) {
	ts, db := newTestServer(t)
	auth := signupLogin(t, ts.URL, "creatework-fields@b.com")

	const forcedID = "11111111-1111-1111-1111-111111111111"
	code, work := postWork(t, ts.URL, auth, map[string]any{
		"title":      "  Moby-Dick  ",
		"id":         forcedID,
		"created_at": "2000-01-01T00:00:00Z",
		"editions": []map[string]any{{
			"id": "22222222-2222-2222-2222-222222222222", "format": "txt",
			"sha256": strings.Repeat("a", 64), "size_bytes": 1, "uploaded_by": 999,
		}},
		"tags": []map[string]any{{"id": "33333333-3333-3333-3333-333333333333", "name": "x"}},
	})
	if code != http.StatusCreated {
		t.Fatalf("create work: want 201, got %d %v", code, work)
	}
	if work["id"] == forcedID {
		t.Fatalf("client-supplied id was honored")
	}
	if work["title"] != "Moby-Dick" {
		t.Fatalf("title not trimmed: %q", work["title"])
	}
	if strings.HasPrefix(fmt.Sprint(work["created_at"]), "2000") {
		t.Fatalf("client-supplied created_at was honored: %v", work["created_at"])
	}
	// The test DB is shared across tests, so count only rows this request
	// could have written.
	checks := map[string]*gorm.DB{
		"editions":  db.Table("editions").Where("work_id = ? OR id = ?", work["id"], "22222222-2222-2222-2222-222222222222"),
		"tags":      db.Table("tags").Where("id = ?", "33333333-3333-3333-3333-333333333333"),
		"work_tags": db.Table("work_tags").Where("work_id = ?", work["id"]),
	}
	for tbl, q := range checks {
		var n int64
		if err := q.Count(&n).Error; err != nil {
			t.Fatalf("count %s: %v", tbl, err)
		}
		if n != 0 {
			t.Fatalf("create work wrote %d %s row(s) from the request body", n, tbl)
		}
	}

	if code, _ := postWork(t, ts.URL, auth, map[string]any{"title": "   "}); code != http.StatusBadRequest {
		t.Fatalf("blank title: want 400, got %d", code)
	}
}

// Uploading bytes that already back an edition of a different work is a 409
// naming the existing edition; the second work gains nothing.
func TestUploadDuplicateAcrossWorksReturns409(t *testing.T) {
	ts, _ := newTestServer(t)
	auth := signupLogin(t, ts.URL, "dup-across-works@b.com")
	_, a := postWork(t, ts.URL, auth, map[string]any{"title": "A"})
	_, b := postWork(t, ts.URL, auth, map[string]any{"title": "B"})
	aID, bID := a["id"].(string), b["id"].(string)

	content := []byte("TestUploadDuplicateAcrossWorksReturns409: same bytes twice\n")
	first := uploadEdition(t, ts.URL, auth, aID, "txt", "en", content)
	if first.StatusCode != http.StatusCreated {
		t.Fatalf("first upload: want 201, got %d %s", first.StatusCode, readBody(first))
	}
	var ed struct{ ID string }
	_ = json.NewDecoder(first.Body).Decode(&ed)

	second := uploadEdition(t, ts.URL, auth, bID, "txt", "en", content)
	if second.StatusCode != http.StatusConflict {
		t.Fatalf("cross-work duplicate: want 409, got %d %s", second.StatusCode, readBody(second))
	}
	var dup struct {
		EditionID string `json:"edition_id"`
		WorkID    string `json:"work_id"`
	}
	_ = json.NewDecoder(second.Body).Decode(&dup)
	if dup.EditionID != ed.ID || dup.WorkID != aID {
		t.Fatalf("409 body: want edition %s on work %s, got %+v", ed.ID, aID, dup)
	}

	resp, _ := http.Get(ts.URL + "/works/" + bID)
	var got struct {
		Editions []struct{ ID string } `json:"editions"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&got)
	if len(got.Editions) != 0 {
		t.Fatalf("work B gained editions after a rejected duplicate: %+v", got.Editions)
	}
}

func TestAuthRequiredForWrites(t *testing.T) {
	ts, _ := newTestServer(t)

	body := mustJSON(t, map[string]string{"title": "x"})
	resp, err := http.Post(ts.URL+"/works", "application/json", body)
	if err != nil {
		t.Fatalf("post: %v", err)
	}
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", resp.StatusCode)
	}
}

func TestCORSPreflight(t *testing.T) {
	ts, _ := newTestServer(t)

	// Browsers send OPTIONS with these headers before a cross-origin POST.
	// Our corsForDev middleware must answer 204 *before* mux's method
	// matcher returns 405 — that's why we wrap the router rather than use
	// r.Use(...). Regression net for that wiring.
	req, _ := http.NewRequest(http.MethodOptions, ts.URL+"/auth/login", nil)
	req.Header.Set("Origin", "http://example.test")
	req.Header.Set("Access-Control-Request-Method", "POST")
	req.Header.Set("Access-Control-Request-Headers", "Content-Type")

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("preflight: %v", err)
	}
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("preflight: expected 204, got %d body=%s", resp.StatusCode, readBody(resp))
	}
	if got := resp.Header.Get("Access-Control-Allow-Origin"); got != "http://example.test" {
		t.Fatalf("preflight: Allow-Origin = %q, want %q", got, "http://example.test")
	}
	if got := resp.Header.Get("Access-Control-Allow-Methods"); got == "" {
		t.Fatalf("preflight: Allow-Methods missing")
	}
}

func TestCORSExposesAuthorizationOnRealRequest(t *testing.T) {
	ts, _ := newTestServer(t)

	signup := mustJSON(t, map[string]string{
		"email": "cors@example.com", "password": testPassword, "name": "C",
	})
	if r, err := http.Post(ts.URL+"/auth/signup", "application/json", signup); err != nil ||
		r.StatusCode != http.StatusCreated {
		t.Fatalf("signup: err=%v code=%d", err, statusOf(r))
	}

	login := mustJSON(t, map[string]string{"email": "cors@example.com", "password": testPassword})
	req, _ := http.NewRequest(http.MethodPost, ts.URL+"/auth/login", login)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Origin", "http://example.test")
	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("login: err=%v code=%d", err, statusOf(resp))
	}
	// Browsers can't read Authorization on a cross-origin response unless
	// it's listed in Access-Control-Expose-Headers.
	if exposed := resp.Header.Get("Access-Control-Expose-Headers"); !strings.Contains(exposed, "Authorization") {
		t.Fatalf("Expose-Headers missing Authorization: %q", exposed)
	}
}

// An origin outside the allowlist must not be reflected — otherwise the
// allowlist is decorative. The preflight still returns 204 (no Allow-Origin
// header means the browser blocks the real request).
func TestCORSRejectsUnlistedOrigin(t *testing.T) {
	ts, _ := newTestServer(t) // allowlist = {http://example.test}

	req, _ := http.NewRequest(http.MethodOptions, ts.URL+"/auth/login", nil)
	req.Header.Set("Origin", "http://evil.test")
	req.Header.Set("Access-Control-Request-Method", "POST")

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("preflight: %v", err)
	}
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("preflight: expected 204, got %d", resp.StatusCode)
	}
	if got := resp.Header.Get("Access-Control-Allow-Origin"); got != "" {
		t.Fatalf("Allow-Origin reflected for unlisted origin: %q", got)
	}
}

// preflightAllowOrigin sends an OPTIONS preflight from origin and returns the
// reflected Access-Control-Allow-Origin (empty if not reflected).
func preflightAllowOrigin(t *testing.T, baseURL, origin string) string {
	t.Helper()
	req, _ := http.NewRequest(http.MethodOptions, baseURL+"/auth/login", nil)
	req.Header.Set("Origin", origin)
	req.Header.Set("Access-Control-Request-Method", "POST")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("preflight from %s: %v", origin, err)
	}
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("preflight from %s: expected 204, got %d", origin, resp.StatusCode)
	}
	return resp.Header.Get("Access-Control-Allow-Origin")
}

// In dev mode (no explicit allowlist), localhost is always allowed; a private
// LAN IP is allowed only when AllowPrivateLAN is opted in.
func TestCORSDevModePrivateLAN(t *testing.T) {
	const lan = "http://192.168.29.234:8080"

	// Flag off: localhost allowed, private LAN rejected.
	off, _ := newTestDeps(t)
	tsOff := newServer(t, off)
	if got := preflightAllowOrigin(t, tsOff.URL, "http://localhost:3000"); got != "http://localhost:3000" {
		t.Fatalf("localhost should be allowed in dev mode, got %q", got)
	}
	if got := preflightAllowOrigin(t, tsOff.URL, lan); got != "" {
		t.Fatalf("private LAN reflected without opt-in: %q", got)
	}

	// Flag on: private LAN now allowed.
	on, _ := newTestDeps(t)
	on.AllowPrivateLAN = true
	tsOn := newServer(t, on)
	if got := preflightAllowOrigin(t, tsOn.URL, lan); got != lan {
		t.Fatalf("private LAN should be allowed with opt-in, got %q", got)
	}
	// A public IP is still rejected even with the flag on.
	if got := preflightAllowOrigin(t, tsOn.URL, "http://8.8.8.8"); got != "" {
		t.Fatalf("public IP reflected with private-LAN opt-in: %q", got)
	}
}

// --- Contribution slice (2.1) -----------------------------------------------

func TestContributionSubmitAndList(t *testing.T) {
	ts, _ := newTestServer(t)
	auth := signupAndLogin(t, ts.URL, "contrib1@x.com", testPassword, "C1")
	workID := createWork(t, ts.URL, auth, "Moby-Dick", "Melville")

	resp := submitContribution(t, ts.URL, auth, workID, map[string]any{
		"title": "Moby Dick",
	})
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("submit: code=%d body=%s", resp.StatusCode, readBody(resp))
	}
	var c struct {
		ID     string `json:"id"`
		Status string `json:"status"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&c); err != nil {
		t.Fatalf("decode contribution: %v", err)
	}
	if c.Status != "pending" {
		t.Fatalf("status=%s want pending", c.Status)
	}

	listResp, err := http.Get(ts.URL + "/contributions?status=pending")
	if err != nil || listResp.StatusCode != http.StatusOK {
		t.Fatalf("list: err=%v code=%d", err, statusOf(listResp))
	}
	var list []struct {
		ID string `json:"id"`
	}
	if err := json.NewDecoder(listResp.Body).Decode(&list); err != nil {
		t.Fatalf("decode list: %v", err)
	}
	found := false
	for _, x := range list {
		if x.ID == c.ID {
			found = true
		}
	}
	if !found {
		t.Fatalf("submitted contribution %s not in pending list %+v", c.ID, list)
	}
}

func TestContributionApproveUpdatesWork(t *testing.T) {
	ts, db := newTestServer(t)
	auth := signupAndLogin(t, ts.URL, "contrib2@x.com", testPassword, "C2")
	grantModerator(t, db, "contrib2@x.com")
	workID := createWork(t, ts.URL, auth, "Old Title", "Old Author")

	resp := submitContribution(t, ts.URL, auth, workID, map[string]any{
		"title":   "New Title",
		"authors": "New Author",
	})
	var c struct {
		ID string `json:"id"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&c)

	if r := postAuthed(t, ts.URL+"/contributions/"+c.ID+"/approve", auth); r.StatusCode != http.StatusOK {
		t.Fatalf("approve: code=%d body=%s", r.StatusCode, readBody(r))
	}

	workResp, _ := http.Get(ts.URL + "/works/" + workID)
	var w struct {
		Title   string `json:"title"`
		Authors string `json:"authors"`
	}
	if err := json.NewDecoder(workResp.Body).Decode(&w); err != nil {
		t.Fatalf("decode work: %v", err)
	}
	if w.Title != "New Title" || w.Authors != "New Author" {
		t.Fatalf("patch not applied: %+v", w)
	}
}

func TestContributionRejectDoesNotUpdateWork(t *testing.T) {
	ts, db := newTestServer(t)
	auth := signupAndLogin(t, ts.URL, "contrib3@x.com", testPassword, "C3")
	grantModerator(t, db, "contrib3@x.com")
	workID := createWork(t, ts.URL, auth, "Original", "Author A")

	resp := submitContribution(t, ts.URL, auth, workID, map[string]any{"title": "Different"})
	var c struct {
		ID string `json:"id"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&c)

	if r := postAuthed(t, ts.URL+"/contributions/"+c.ID+"/reject", auth); r.StatusCode != http.StatusOK {
		t.Fatalf("reject: code=%d body=%s", r.StatusCode, readBody(r))
	}

	workResp, _ := http.Get(ts.URL + "/works/" + workID)
	var w struct {
		Title string `json:"title"`
	}
	_ = json.NewDecoder(workResp.Body).Decode(&w)
	if w.Title != "Original" {
		t.Fatalf("title changed after reject: %s", w.Title)
	}
}

func TestContributionDoubleApproveReturns409(t *testing.T) {
	ts, db := newTestServer(t)
	auth := signupAndLogin(t, ts.URL, "contrib4@x.com", testPassword, "C4")
	grantModerator(t, db, "contrib4@x.com")
	workID := createWork(t, ts.URL, auth, "W", "")

	resp := submitContribution(t, ts.URL, auth, workID, map[string]any{"title": "T"})
	var c struct {
		ID string `json:"id"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&c)

	if r := postAuthed(t, ts.URL+"/contributions/"+c.ID+"/approve", auth); r.StatusCode != http.StatusOK {
		t.Fatalf("first approve: code=%d", r.StatusCode)
	}
	r2 := postAuthed(t, ts.URL+"/contributions/"+c.ID+"/approve", auth)
	if r2.StatusCode != http.StatusConflict {
		t.Fatalf("second approve: want 409, got %d body=%s", r2.StatusCode, readBody(r2))
	}
}

func TestContributionInvalidPatchRejectedAtSubmit(t *testing.T) {
	ts, _ := newTestServer(t)
	auth := signupAndLogin(t, ts.URL, "contrib5@x.com", testPassword, "C5")
	workID := createWork(t, ts.URL, auth, "Title A", "")

	for name, patch := range map[string]map[string]any{
		"unknown key":    {"title": "Real Update", "bogus": "junk"},
		"only unknown":   {"bogus": "junk"},
		"year as string": {"publication_year": "abc"},
		"empty title":    {"title": ""},
	} {
		resp := submitContribution(t, ts.URL, auth, workID, patch)
		if resp.StatusCode != http.StatusBadRequest {
			t.Fatalf("%s: want 400, got %d body=%s", name, resp.StatusCode, readBody(resp))
		}
		if body := readBody(resp); !strings.Contains(body, "invalid patch") {
			t.Fatalf("%s: want a descriptive message, got %q", name, body)
		}
	}

	// Nothing was queued.
	resp, _ := http.Get(ts.URL + "/contributions?status=pending")
	var cs []struct {
		WorkID string `json:"work_id"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&cs)
	for _, c := range cs {
		if c.WorkID == workID {
			t.Fatalf("invalid patch was queued: %+v", cs)
		}
	}
}

// --- Moderator gate (2.2) --------------------------------------------------

func TestModeratorRequiredForApprove(t *testing.T) {
	ts, _ := newTestServer(t)
	auth := signupAndLogin(t, ts.URL, "nonmod1@x.com", testPassword, "NM1")
	// Deliberately NOT promoted. Submit a contribution against a work we
	// create, then attempt to approve as a plain user — expect 403.
	workID := createWork(t, ts.URL, auth, "T", "")
	resp := submitContribution(t, ts.URL, auth, workID, map[string]any{"title": "New"})
	var c struct {
		ID string `json:"id"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&c)

	r := postAuthed(t, ts.URL+"/contributions/"+c.ID+"/approve", auth)
	if r.StatusCode != http.StatusForbidden {
		t.Fatalf("approve as non-mod: want 403, got %d body=%s", r.StatusCode, readBody(r))
	}
}

func TestModeratorRequiredForReject(t *testing.T) {
	ts, _ := newTestServer(t)
	auth := signupAndLogin(t, ts.URL, "nonmod2@x.com", testPassword, "NM2")
	workID := createWork(t, ts.URL, auth, "T", "")
	resp := submitContribution(t, ts.URL, auth, workID, map[string]any{"title": "New"})
	var c struct {
		ID string `json:"id"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&c)

	r := postAuthed(t, ts.URL+"/contributions/"+c.ID+"/reject", auth)
	if r.StatusCode != http.StatusForbidden {
		t.Fatalf("reject as non-mod: want 403, got %d body=%s", r.StatusCode, readBody(r))
	}
}

func TestAuthMeReturnsCurrentUser(t *testing.T) {
	ts, db := newTestServer(t)
	auth := signupAndLogin(t, ts.URL, "meendpoint@x.com", testPassword, "Me Tester")

	// Initial fetch: not a moderator.
	req, _ := http.NewRequest(http.MethodGet, ts.URL+"/auth/me", nil)
	req.Header.Set("Authorization", auth)
	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("me: err=%v code=%d body=%s", err, statusOf(resp), readBody(resp))
	}
	var m struct {
		ID          uint   `json:"id"`
		Email       string `json:"email"`
		Name        string `json:"name"`
		IsModerator bool   `json:"is_moderator"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&m); err != nil {
		t.Fatalf("decode me: %v", err)
	}
	if m.Email != "meendpoint@x.com" || m.Name != "Me Tester" || m.ID == 0 {
		t.Fatalf("bad me payload: %+v", m)
	}
	if m.IsModerator {
		t.Fatalf("freshly signed-up user should not be a moderator: %+v", m)
	}

	// Promote and re-fetch — IsModerator should flip without a re-login,
	// confirming /auth/me reads live DB state rather than the JWT.
	grantModerator(t, db, "meendpoint@x.com")
	req2, _ := http.NewRequest(http.MethodGet, ts.URL+"/auth/me", nil)
	req2.Header.Set("Authorization", auth)
	resp2, _ := http.DefaultClient.Do(req2)
	var m2 struct {
		IsModerator bool `json:"is_moderator"`
	}
	_ = json.NewDecoder(resp2.Body).Decode(&m2)
	if !m2.IsModerator {
		t.Fatalf("after promote, /auth/me should report is_moderator=true")
	}
}

func TestAuthMeRequiresAuth(t *testing.T) {
	ts, _ := newTestServer(t)
	resp, err := http.Get(ts.URL + "/auth/me")
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("unauth /auth/me: want 401, got %d", resp.StatusCode)
	}
}

// --- Search slice (2.3) ----------------------------------------------------

func TestSearchWorksMatchesTitleAuthorsDescription(t *testing.T) {
	ts, _ := newTestServer(t)
	auth := signupAndLogin(t, ts.URL, "search1@x.com", testPassword, "S1")

	// Seed three works with distinct title / authors / description so we can
	// assert the LIKE fan-out hits each column independently.
	createWorkFull(t, ts.URL, auth, "Moby-Dick", "Herman Melville", "")
	createWorkFull(t, ts.URL, auth, "Mobile Phones Today", "", "")
	createWorkFull(t, ts.URL, auth, "Pride and Prejudice", "Jane Austen",
		"A regency-era novel about manners and marriage.")

	// "mob" should match the two Mob* titles, case-insensitive.
	titles := searchTitles(t, ts.URL, "MOB")
	if !contains(titles, "Moby-Dick") || !contains(titles, "Mobile Phones Today") {
		t.Fatalf("expected Mob* titles, got %v", titles)
	}
	if contains(titles, "Pride and Prejudice") {
		t.Fatalf("Pride should not match 'mob': %v", titles)
	}

	// "austen" matches via the authors column.
	gotAuthor := searchTitles(t, ts.URL, "austen")
	if len(gotAuthor) != 1 || gotAuthor[0] != "Pride and Prejudice" {
		t.Fatalf("authors search: want only Pride, got %v", gotAuthor)
	}

	// "regency" matches via the description column.
	gotDesc := searchTitles(t, ts.URL, "regency")
	if len(gotDesc) != 1 || gotDesc[0] != "Pride and Prejudice" {
		t.Fatalf("description search: want only Pride, got %v", gotDesc)
	}
}

func TestSearchWorksEmptyQueryReturns400(t *testing.T) {
	ts, _ := newTestServer(t)
	resp, err := http.Get(ts.URL + "/works/search?q=")
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("empty q: want 400, got %d body=%s", resp.StatusCode, readBody(resp))
	}
}

func TestSearchWorksNoMatchReturnsEmptyArray(t *testing.T) {
	ts, _ := newTestServer(t)
	auth := signupAndLogin(t, ts.URL, "search2@x.com", testPassword, "S2")
	createWorkFull(t, ts.URL, auth, "Foo", "", "")

	got := searchTitles(t, ts.URL, "zzz-not-a-word")
	if len(got) != 0 {
		t.Fatalf("expected empty result for unmatched query, got %v", got)
	}
}

func TestSearchWorksOrderedByCreatedAtDesc(t *testing.T) {
	ts, _ := newTestServer(t)
	auth := signupAndLogin(t, ts.URL, "search3@x.com", testPassword, "S3")

	// Create three works in known order; ordering is by created_at DESC so
	// the last-created should appear first.
	createWorkFull(t, ts.URL, auth, "Alpha test book", "", "")
	createWorkFull(t, ts.URL, auth, "Beta test book", "", "")
	createWorkFull(t, ts.URL, auth, "Gamma test book", "", "")

	got := searchTitles(t, ts.URL, "test book")
	if len(got) != 3 {
		t.Fatalf("expected 3, got %d: %v", len(got), got)
	}
	if got[0] != "Gamma test book" || got[1] != "Beta test book" || got[2] != "Alpha test book" {
		t.Fatalf("ordering: want Gamma,Beta,Alpha (DESC), got %v", got)
	}
}

// The sqlite fallback approximates ts_rank: a title hit outranks an author
// hit, which outranks a description-only hit — regardless of recency.
func TestSearchWorksRanksTitleMatchesFirst(t *testing.T) {
	ts, _ := newTestServer(t)
	auth := signupAndLogin(t, ts.URL, "search5@x.com", testPassword, "S5")

	createWorkFull(t, ts.URL, auth, "Leviathan", "Thomas Hobbes", "")
	createWorkFull(t, ts.URL, auth, "Whale Facts", "Leviathan Press", "")
	createWorkFull(t, ts.URL, auth, "Sea Stories", "", "Mentions a leviathan once.")

	got := searchTitles(t, ts.URL, "leviathan")
	want := []string{"Leviathan", "Whale Facts", "Sea Stories"}
	if len(got) != 3 || got[0] != want[0] || got[1] != want[1] || got[2] != want[2] {
		t.Fatalf("ranking: want %v, got %v", want, got)
	}
}

func TestSearchWorksTreatsLikeWildcardsLiterally(t *testing.T) {
	ts, _ := newTestServer(t)
	auth := signupAndLogin(t, ts.URL, "search4@x.com", testPassword, "S4")

	createWorkFull(t, ts.URL, auth, "snake_case handbook", "", "")
	createWorkFull(t, ts.URL, auth, "100% Pure Prose", "", "")
	createWorkFull(t, ts.URL, auth, "Plain title without metachars", "", "")

	underscore := searchTitles(t, ts.URL, "_")
	if !contains(underscore, "snake_case handbook") {
		t.Fatalf("'_' should match the literal underscore title, got %v", underscore)
	}
	if contains(underscore, "Plain title without metachars") || contains(underscore, "100% Pure Prose") {
		t.Fatalf("'_' must not act as a wildcard, got %v", underscore)
	}

	percent := searchTitles(t, ts.URL, "%")
	if !contains(percent, "100% Pure Prose") {
		t.Fatalf("'%%' should match the literal percent title, got %v", percent)
	}
	if contains(percent, "Plain title without metachars") || contains(percent, "snake_case handbook") {
		t.Fatalf("'%%' must not act as a wildcard, got %v", percent)
	}
}

func TestLibraryUpsertRequiresAuth(t *testing.T) {
	ts, _ := newTestServer(t)
	auth := signupAndLogin(t, ts.URL, "lib0@x.com", testPassword, "L0")
	wid := createWork(t, ts.URL, auth, "Dune", "Herbert")

	// No Authorization header -> 401.
	body := mustJSON(t, map[string]any{"status": "reading"})
	req, _ := http.NewRequest(http.MethodPut, ts.URL+"/me/library/"+wid, body)
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("unauth PUT: err=%v code=%d", err, statusOf(resp))
	}
}

func TestLibraryUpsertThenGetEmbedsWork(t *testing.T) {
	ts, _ := newTestServer(t)
	auth := signupAndLogin(t, ts.URL, "lib1@x.com", testPassword, "L1")
	wid := createWork(t, ts.URL, auth, "Dune", "Herbert")

	up := putLibrary(t, ts.URL, auth, wid, map[string]any{
		"status":           "reading",
		"shelf":            "sci-fi",
		"progress_percent": 40,
		"rating":           5,
	})
	if up.Status != "reading" || up.Shelf != "sci-fi" || up.ProgressPercent != 40 {
		t.Fatalf("upsert echo wrong: %+v", up)
	}
	if up.Rating == nil || *up.Rating != 5 {
		t.Fatalf("rating not set: %+v", up.Rating)
	}
	if up.StartedAt == nil {
		t.Fatalf("reading should stamp started_at")
	}

	// GET round-trips with the embedded work.
	req, _ := http.NewRequest(http.MethodGet, ts.URL+"/me/library/"+wid, nil)
	req.Header.Set("Authorization", auth)
	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("get: err=%v code=%d", err, statusOf(resp))
	}
	var got libraryEntry
	if err := json.NewDecoder(resp.Body).Decode(&got); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if got.Work == nil || got.Work.Title != "Dune" {
		t.Fatalf("work not embedded: %+v", got.Work)
	}
}

func TestLibraryListFiltersByStatus(t *testing.T) {
	ts, _ := newTestServer(t)
	auth := signupAndLogin(t, ts.URL, "lib2@x.com", testPassword, "L2")
	w1 := createWork(t, ts.URL, auth, "A", "")
	w2 := createWork(t, ts.URL, auth, "B", "")
	putLibrary(t, ts.URL, auth, w1, map[string]any{"status": "reading"})
	putLibrary(t, ts.URL, auth, w2, map[string]any{"status": "read"})

	req, _ := http.NewRequest(http.MethodGet, ts.URL+"/me/library?status=reading", nil)
	req.Header.Set("Authorization", auth)
	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("list: err=%v code=%d", err, statusOf(resp))
	}
	var got []libraryEntry
	if err := json.NewDecoder(resp.Body).Decode(&got); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(got) != 1 || got[0].WorkID != w1 {
		t.Fatalf("status filter: want only w1 reading, got %+v", got)
	}
}

func TestLibraryDeleteThenGet404(t *testing.T) {
	ts, _ := newTestServer(t)
	auth := signupAndLogin(t, ts.URL, "lib3@x.com", testPassword, "L3")
	wid := createWork(t, ts.URL, auth, "Dune", "")
	putLibrary(t, ts.URL, auth, wid, map[string]any{"status": "want"})

	req, _ := http.NewRequest(http.MethodDelete, ts.URL+"/me/library/"+wid, nil)
	req.Header.Set("Authorization", auth)
	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != http.StatusNoContent {
		t.Fatalf("delete: err=%v code=%d", err, statusOf(resp))
	}

	req2, _ := http.NewRequest(http.MethodGet, ts.URL+"/me/library/"+wid, nil)
	req2.Header.Set("Authorization", auth)
	resp2, _ := http.DefaultClient.Do(req2)
	if statusOf(resp2) != http.StatusNotFound {
		t.Fatalf("get after delete: code=%d, want 404", statusOf(resp2))
	}
}

func TestLibraryUpsertInvalidStatus400(t *testing.T) {
	ts, _ := newTestServer(t)
	auth := signupAndLogin(t, ts.URL, "lib4@x.com", testPassword, "L4")
	wid := createWork(t, ts.URL, auth, "Dune", "")

	body := mustJSON(t, map[string]any{"status": "skimming"})
	req, _ := http.NewRequest(http.MethodPut, ts.URL+"/me/library/"+wid, body)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", auth)
	resp, _ := http.DefaultClient.Do(req)
	if statusOf(resp) != http.StatusBadRequest {
		t.Fatalf("invalid status: code=%d, want 400", statusOf(resp))
	}
}

// libraryEntry mirrors the JSON the /me/library endpoints emit (a subset of
// internal/library.UserBook plus the embedded work).
type libraryEntry struct {
	WorkID          string  `json:"work_id"`
	Status          string  `json:"status"`
	Shelf           string  `json:"shelf"`
	ProgressPercent int     `json:"progress_percent"`
	Rating          *int    `json:"rating"`
	StartedAt       *string `json:"started_at"`
	Work            *struct {
		Title string `json:"title"`
	} `json:"work"`
}

func putLibrary(t *testing.T, base, auth, workID string, body map[string]any) libraryEntry {
	t.Helper()
	req, _ := http.NewRequest(http.MethodPut, base+"/me/library/"+workID, mustJSON(t, body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", auth)
	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("put library: err=%v code=%d body=%s", err, statusOf(resp), readBody(resp))
	}
	var out libraryEntry
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		t.Fatalf("decode library entry: %v", err)
	}
	return out
}

func TestRecommendationsRequireAuth(t *testing.T) {
	ts, _ := newTestServer(t)
	resp, err := http.Get(ts.URL + "/me/recommendations")
	if err != nil || resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("unauth GET: err=%v code=%d", err, statusOf(resp))
	}
}

func TestRecommendationsReturnsContentMatch(t *testing.T) {
	ts, _ := newTestServer(t)
	auth := signupAndLogin(t, ts.URL, "rec@x.com", testPassword, "Rec")

	// Two works by the same author; the user likes one, expects the other.
	liked := createWorkFull(t, ts.URL, auth, "Dune", "Frank Herbert", "")
	sibling := createWorkFull(t, ts.URL, auth, "Dune Messiah", "Frank Herbert", "")
	putLibrary(t, ts.URL, auth, liked, map[string]any{"status": "read", "rating": 5})

	req, _ := http.NewRequest(http.MethodGet, ts.URL+"/me/recommendations", nil)
	req.Header.Set("Authorization", auth)
	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("recommend: err=%v code=%d", err, statusOf(resp))
	}
	var recs []struct {
		Work   struct{ ID string } `json:"work"`
		Reason string              `json:"reason"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&recs); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(recs) == 0 {
		t.Fatalf("expected at least one recommendation")
	}
	if recs[0].Work.ID != sibling {
		t.Fatalf("expected the same-author sibling first, got %+v", recs)
	}
	if recs[0].Reason == "" {
		t.Fatalf("recommendation should carry a reason")
	}
	// The already-liked work must never be recommended back.
	for _, r := range recs {
		if r.Work.ID == liked {
			t.Fatalf("a work already in the library was recommended")
		}
	}
}

func TestRecommendationsMatrixFactorizationPersonalized(t *testing.T) {
	ts, db := newTestServer(t)
	auth := signupAndLogin(t, ts.URL, "mfmain@x.com", testPassword, "Main")

	// Cluster A (3 works) and cluster B (3 works).
	a := []string{
		createWork(t, ts.URL, auth, "A0", "Author A"),
		createWork(t, ts.URL, auth, "A1", "Author A"),
		createWork(t, ts.URL, auth, "A2", "Author A"),
	}
	b := []string{
		createWork(t, ts.URL, auth, "B0", "Author B"),
		createWork(t, ts.URL, auth, "B1", "Author B"),
		createWork(t, ts.URL, auth, "B2", "Author B"),
	}

	// User 1 likes A0 + A1 (so A2 is the unseen cluster-A work we expect back).
	putLibrary(t, ts.URL, auth, a[0], map[string]any{"status": "read", "rating": 5})
	putLibrary(t, ts.URL, auth, a[1], map[string]any{"status": "read", "rating": 5})

	// Synthetic co-users establish the collaborative pattern: six like all of
	// A, six like all of B. Seeded directly (the trainer only reads
	// user_books) under ids far above any real signup — the smoke tests share
	// one database, so the main user's id depends on which tests ran first.
	const coUser = uint(1_000_000)
	for u := coUser; u < coUser+6; u++ {
		for _, w := range a {
			seedUserBook(t, db, u, w, "read", 5)
		}
	}
	for u := coUser + 6; u < coUser+12; u++ {
		for _, w := range b {
			seedUserBook(t, db, u, w, "read", 5)
		}
	}

	// Train synchronously, then query the HTTP endpoint.
	if err := recommendation.NewTrainer(db, recommendation.DefaultConfig()).
		Train(context.Background()); err != nil {
		t.Fatalf("train: %v", err)
	}

	recs := getRecommendations(t, ts.URL, auth)
	if len(recs) == 0 {
		t.Fatalf("expected recommendations")
	}
	// A2 (unseen cluster-A work) should appear and rank above every B work.
	posA2, firstB := -1, len(recs)
	bset := map[string]bool{b[0]: true, b[1]: true, b[2]: true}
	for i, r := range recs {
		if r.Work.ID == a[2] && posA2 == -1 {
			posA2 = i
		}
		if bset[r.Work.ID] && i < firstB {
			firstB = i
		}
	}
	if posA2 == -1 {
		t.Fatalf("expected the unseen cluster-A work A2 to be recommended; got %+v", recs)
	}
	if posA2 > firstB {
		t.Fatalf("A2 (pos %d) should rank above the first B work (pos %d)", posA2, firstB)
	}
	// A0/A1 are in the library → never recommended back.
	for _, r := range recs {
		if r.Work.ID == a[0] || r.Work.ID == a[1] {
			t.Fatalf("a library work was recommended back")
		}
	}
}

func TestRecommendationDismissHidesWork(t *testing.T) {
	ts, _ := newTestServer(t)
	auth := signupAndLogin(t, ts.URL, "dismiss@x.com", testPassword, "D")
	a0 := createWork(t, ts.URL, auth, "Dune", "Frank Herbert")
	a1 := createWork(t, ts.URL, auth, "Dune Messiah", "Frank Herbert")
	putLibrary(t, ts.URL, auth, a0, map[string]any{"status": "read", "rating": 5})

	// Content fallback recommends the sibling A1.
	recs := getRecommendations(t, ts.URL, auth)
	if !containsWork(recs, a1) {
		t.Fatalf("expected A1 recommended before dismiss; got %+v", recs)
	}

	// Dismiss A1.
	req, _ := http.NewRequest(http.MethodPost, ts.URL+"/me/recommendations/"+a1+"/dismiss", nil)
	req.Header.Set("Authorization", auth)
	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != http.StatusNoContent {
		t.Fatalf("dismiss: err=%v code=%d", err, statusOf(resp))
	}

	// It's gone next call.
	recs2 := getRecommendations(t, ts.URL, auth)
	if containsWork(recs2, a1) {
		t.Fatalf("dismissed work A1 should no longer be recommended; got %+v", recs2)
	}

	// Undo brings it back; undoing again is a no-op.
	for i := 0; i < 2; i++ {
		req, _ = http.NewRequest(http.MethodDelete, ts.URL+"/me/recommendations/"+a1+"/dismiss", nil)
		req.Header.Set("Authorization", auth)
		resp, err = http.DefaultClient.Do(req)
		if err != nil || resp.StatusCode != http.StatusNoContent {
			t.Fatalf("undismiss #%d: err=%v code=%d", i+1, err, statusOf(resp))
		}
	}
	if recs3 := getRecommendations(t, ts.URL, auth); !containsWork(recs3, a1) {
		t.Fatalf("undismissed work A1 should be recommended again; got %+v", recs3)
	}
}

type recItem struct {
	Work   struct{ ID string } `json:"work"`
	Reason string              `json:"reason"`
}

func getRecommendations(t *testing.T, base, auth string) []recItem {
	t.Helper()
	req, _ := http.NewRequest(http.MethodGet, base+"/me/recommendations", nil)
	req.Header.Set("Authorization", auth)
	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("recommendations: err=%v code=%d body=%s", err, statusOf(resp), readBody(resp))
	}
	var recs []recItem
	if err := json.NewDecoder(resp.Body).Decode(&recs); err != nil {
		t.Fatalf("decode recommendations: %v", err)
	}
	return recs
}

func containsWork(recs []recItem, id string) bool {
	for _, r := range recs {
		if r.Work.ID == id {
			return true
		}
	}
	return false
}

// seedUserBook inserts a user_books row directly (for synthetic co-users that
// drive collaborative signal without going through the HTTP API).
func seedUserBook(t *testing.T, db *gorm.DB, userID uint, workID, status string, rating int) {
	t.Helper()
	if err := db.Table("user_books").Create(map[string]any{
		"id":      uuid.New(),
		"user_id": userID,
		"work_id": uuid.MustParse(workID),
		"status":  status,
		"rating":  rating,
	}).Error; err != nil {
		t.Fatalf("seed user_book: %v", err)
	}
}

// --- helpers ---------------------------------------------------------------

func signupAndLogin(t *testing.T, base, email, password, name string) string {
	t.Helper()
	signup := mustJSON(t, map[string]string{"email": email, "password": password, "name": name})
	if r, err := http.Post(base+"/auth/signup", "application/json", signup); err != nil || r.StatusCode != http.StatusCreated {
		t.Fatalf("signup %s: err=%v code=%d", email, err, statusOf(r))
	}
	return "Bearer " + loginPair(t, base, email, password).AccessToken
}

func createWork(t *testing.T, base, auth, title, authors string) string {
	t.Helper()
	return createWorkFull(t, base, auth, title, authors, "")
}

func createWorkFull(t *testing.T, base, auth, title, authors, description string) string {
	t.Helper()
	body := mustJSON(t, map[string]any{
		"title":       title,
		"authors":     authors,
		"description": description,
	})
	req, _ := http.NewRequest(http.MethodPost, base+"/works", body)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", auth)
	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != http.StatusCreated {
		t.Fatalf("create work: err=%v code=%d body=%s", err, statusOf(resp), readBody(resp))
	}
	var w struct {
		ID string `json:"id"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&w); err != nil {
		t.Fatalf("decode work: %v", err)
	}
	return w.ID
}

func searchTitles(t *testing.T, base, q string) []string {
	t.Helper()
	resp, err := http.Get(base + "/works/search?q=" + url.QueryEscape(q))
	if err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("search %q: err=%v code=%d body=%s", q, err, statusOf(resp), readBody(resp))
	}
	var works []struct {
		Title string `json:"title"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&works); err != nil {
		t.Fatalf("decode search %q: %v", q, err)
	}
	out := make([]string, len(works))
	for i, w := range works {
		out[i] = w.Title
	}
	return out
}

func contains(xs []string, s string) bool {
	for _, x := range xs {
		if x == s {
			return true
		}
	}
	return false
}

func submitContribution(t *testing.T, base, auth, workID string, patch map[string]any) *http.Response {
	t.Helper()
	body := mustJSON(t, map[string]any{"patch": patch})
	req, _ := http.NewRequest(http.MethodPost, base+"/works/"+workID+"/contributions", body)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", auth)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("submit contribution: %v", err)
	}
	return resp
}

func postAuthed(t *testing.T, url, auth string) *http.Response {
	t.Helper()
	req, _ := http.NewRequest(http.MethodPost, url, nil)
	req.Header.Set("Authorization", auth)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("post %s: %v", url, err)
	}
	return resp
}

func uploadEdition(t *testing.T, base, auth, workID, format, lang string, fileBytes []byte) *http.Response {
	t.Helper()
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	_ = mw.WriteField("format", format)
	_ = mw.WriteField("language", lang)
	fw, err := mw.CreateFormFile("file", "book."+format)
	if err != nil {
		t.Fatalf("create form file: %v", err)
	}
	if _, err := fw.Write(fileBytes); err != nil {
		t.Fatalf("write form file: %v", err)
	}
	if err := mw.Close(); err != nil {
		t.Fatalf("close mw: %v", err)
	}

	req, _ := http.NewRequest(http.MethodPost, base+"/works/"+workID+"/editions", &buf)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("Authorization", auth)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("upload: %v", err)
	}
	return resp
}

func mustJSON(t *testing.T, v any) *bytes.Buffer {
	t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	return bytes.NewBuffer(b)
}

func statusOf(r *http.Response) int {
	if r == nil {
		return -1
	}
	return r.StatusCode
}

func readBody(r *http.Response) string {
	if r == nil {
		return ""
	}
	b, _ := io.ReadAll(r.Body)
	return string(b)
}

// --- Moderation takedowns (#13) ---------------------------------------------

func deleteAuthed(t *testing.T, url, auth string, body any) *http.Response {
	t.Helper()
	var rdr io.Reader
	if body != nil {
		rdr = mustJSON(t, body)
	}
	req, _ := http.NewRequest(http.MethodDelete, url, rdr)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", auth)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("delete %s: %v", url, err)
	}
	return resp
}

func editionID(t *testing.T, resp *http.Response) string {
	t.Helper()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("upload: code=%d body=%s", resp.StatusCode, readBody(resp))
	}
	var ed struct {
		ID string `json:"id"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&ed)
	return ed.ID
}

func TestModeratorRemovesEdition(t *testing.T) {
	ts, db := newTestServer(t)
	mod := signupAndLogin(t, ts.URL, "takedown1@x.com", testPassword, "Mod")
	grantModerator(t, db, "takedown1@x.com")
	user := signupAndLogin(t, ts.URL, "takedown1u@x.com", testPassword, "User")
	workID := createWork(t, ts.URL, user, "Takedown Work", "")
	content := []byte("infringing bytes\n")
	keep := editionID(t, uploadEdition(t, ts.URL, user, workID, "txt", "en", []byte("fine bytes\n")))
	gone := editionID(t, uploadEdition(t, ts.URL, user, workID, "txt", "en", content))
	url := ts.URL + "/editions/" + gone

	if r := deleteAuthed(t, url, user, map[string]string{"reason": "dmca"}); r.StatusCode != http.StatusForbidden {
		t.Fatalf("non-moderator: want 403, got %d", r.StatusCode)
	}
	if r := deleteAuthed(t, url, mod, map[string]string{"reason": "  "}); r.StatusCode != http.StatusBadRequest {
		t.Fatalf("blank reason: want 400, got %d", r.StatusCode)
	}
	if r := deleteAuthed(t, url, mod, map[string]string{"reason": "DMCA notice #42"}); r.StatusCode != http.StatusNoContent {
		t.Fatalf("remove: want 204, got %d %s", r.StatusCode, readBody(r))
	}
	if r := deleteAuthed(t, url, mod, map[string]string{"reason": "again"}); r.StatusCode != http.StatusNotFound {
		t.Fatalf("remove twice: want 404, got %d", r.StatusCode)
	}

	for _, path := range []string{"/editions/" + gone, "/editions/" + gone + "/download"} {
		if r, _ := http.Get(ts.URL + path); r.StatusCode != http.StatusNotFound {
			t.Fatalf("GET %s after removal: want 404, got %d", path, r.StatusCode)
		}
	}
	workResp, _ := http.Get(ts.URL + "/works/" + workID)
	if body := readBody(workResp); strings.Contains(body, gone) || !strings.Contains(body, keep) {
		t.Fatalf("work should list only the surviving edition: %s", body)
	}

	// Re-uploading the removed bytes doesn't resurrect them.
	if r := uploadEdition(t, ts.URL, user, workID, "txt", "en", content); r.StatusCode != http.StatusConflict {
		t.Fatalf("re-upload of removed file: want 409, got %d %s", r.StatusCode, readBody(r))
	}

	// The audit trail is kept.
	var row struct {
		DeletedBy    uint
		DeleteReason string
	}
	db.Table("editions").Select("deleted_by, delete_reason").Where("id = ?", gone).Take(&row)
	if row.DeleteReason != "DMCA notice #42" || row.DeletedBy == 0 {
		t.Fatalf("audit fields not recorded: %+v", row)
	}
}

func TestModeratorRemovesWork(t *testing.T) {
	ts, db := newTestServer(t)
	mod := signupAndLogin(t, ts.URL, "takedown2@x.com", testPassword, "Mod")
	grantModerator(t, db, "takedown2@x.com")
	workID := createWork(t, ts.URL, mod, "Zyzzyva Removed Title", "")
	ed := editionID(t, uploadEdition(t, ts.URL, mod, workID, "txt", "en", []byte("zyzzyva\n")))
	putLibrary(t, ts.URL, mod, workID, map[string]any{"status": "reading"})

	if r := deleteAuthed(t, ts.URL+"/works/"+workID, mod, map[string]string{"reason": "spam"}); r.StatusCode != http.StatusNoContent {
		t.Fatalf("remove work: want 204, got %d %s", r.StatusCode, readBody(r))
	}

	if r, _ := http.Get(ts.URL + "/works/" + workID); r.StatusCode != http.StatusNotFound {
		t.Fatalf("GET work: want 404, got %d", r.StatusCode)
	}
	if r, _ := http.Get(ts.URL + "/editions/" + ed + "/download"); r.StatusCode != http.StatusNotFound {
		t.Fatalf("download edition of removed work: want 404, got %d", r.StatusCode)
	}
	if titles := searchTitles(t, ts.URL, "zyzzyva"); len(titles) != 0 {
		t.Fatalf("search still finds removed work: %v", titles)
	}
	listResp, _ := http.Get(ts.URL + "/works?limit=200")
	if strings.Contains(readBody(listResp), workID) {
		t.Fatalf("list still includes removed work")
	}

	// Personal library hides it; library writes and contributions 404.
	req, _ := http.NewRequest(http.MethodGet, ts.URL+"/me/library", nil)
	req.Header.Set("Authorization", mod)
	libResp, _ := http.DefaultClient.Do(req)
	if strings.Contains(readBody(libResp), workID) {
		t.Fatalf("library still lists removed work")
	}
	req, _ = http.NewRequest(http.MethodPut, ts.URL+"/me/library/"+workID, mustJSON(t, map[string]any{"status": "read"}))
	req.Header.Set("Authorization", mod)
	if r, _ := http.DefaultClient.Do(req); r.StatusCode != http.StatusNotFound {
		t.Fatalf("library upsert on removed work: want 404, got %d", r.StatusCode)
	}
	if r := submitContribution(t, ts.URL, mod, workID, map[string]any{"title": "x"}); r.StatusCode != http.StatusNotFound {
		t.Fatalf("contribution on removed work: want 404, got %d", r.StatusCode)
	}
}

// --- Sessions: refresh + revocation (#15) ------------------------------------

type tokenPair struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	ExpiresIn    int    `json:"expires_in"`
}

func loginPair(t *testing.T, base, email, password string) tokenPair {
	t.Helper()
	resp, err := http.Post(base+"/auth/login", "application/json",
		mustJSON(t, map[string]string{"email": email, "password": password}))
	if err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("login: err=%v code=%d", err, statusOf(resp))
	}
	var p tokenPair
	if err := json.NewDecoder(resp.Body).Decode(&p); err != nil {
		t.Fatal(err)
	}
	if p.AccessToken == "" || p.RefreshToken == "" {
		t.Fatalf("login: incomplete token pair %+v", p)
	}
	if h := resp.Header.Get("Authorization"); h != "" {
		t.Fatalf("tokens belong in the body only; got Authorization header %q", h)
	}
	return p
}

func getMe(t *testing.T, base, access string) int {
	t.Helper()
	req, _ := http.NewRequest(http.MethodGet, base+"/auth/me", nil)
	req.Header.Set("Authorization", "Bearer "+access)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	return resp.StatusCode
}

func postJSON(t *testing.T, url string, body any) *http.Response {
	t.Helper()
	resp, err := http.Post(url, "application/json", mustJSON(t, body))
	if err != nil {
		t.Fatal(err)
	}
	return resp
}

func TestRefreshLogoutAndLogoutAll(t *testing.T) {
	ts, _ := newTestServer(t)
	signupAndLogin(t, ts.URL, "session1@x.com", testPassword, "S")
	a := loginPair(t, ts.URL, "session1@x.com", testPassword)
	b := loginPair(t, ts.URL, "session1@x.com", testPassword)
	if a.RefreshToken == "" || a.ExpiresIn != 900 {
		t.Fatalf("login pair: %+v", a)
	}

	// Refresh rotates.
	resp := postJSON(t, ts.URL+"/auth/refresh", map[string]string{"refresh_token": a.RefreshToken})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("refresh: %d %s", resp.StatusCode, readBody(resp))
	}
	var a2 tokenPair
	_ = json.NewDecoder(resp.Body).Decode(&a2)
	if a2.RefreshToken == a.RefreshToken || getMe(t, ts.URL, a2.AccessToken) != http.StatusOK {
		t.Fatalf("rotated pair unusable: %+v", a2)
	}
	if r := postJSON(t, ts.URL+"/auth/refresh", map[string]string{}); r.StatusCode != http.StatusBadRequest {
		t.Fatalf("missing refresh token: want 400, got %d", r.StatusCode)
	}

	// Logout ends session b only.
	if r := postJSON(t, ts.URL+"/auth/logout", map[string]string{"refresh_token": b.RefreshToken}); r.StatusCode != http.StatusNoContent {
		t.Fatalf("logout: %d", r.StatusCode)
	}
	if r := postJSON(t, ts.URL+"/auth/refresh", map[string]string{"refresh_token": b.RefreshToken}); r.StatusCode != http.StatusUnauthorized {
		t.Fatalf("refresh after logout: want 401, got %d", r.StatusCode)
	}
	if r := postJSON(t, ts.URL+"/auth/refresh", map[string]string{"refresh_token": a2.RefreshToken}); r.StatusCode != http.StatusOK {
		t.Fatalf("other session after logout: want 200, got %d", r.StatusCode)
	}

	// Logout-all ends every session: nothing can refresh any more. Access
	// tokens are stateless by default, so they run out their (short) life.
	c := loginPair(t, ts.URL, "session1@x.com", testPassword)
	req, _ := http.NewRequest(http.MethodPost, ts.URL+"/auth/logout-all", nil)
	req.Header.Set("Authorization", "Bearer "+c.AccessToken)
	if r, _ := http.DefaultClient.Do(req); r.StatusCode != http.StatusNoContent {
		t.Fatalf("logout-all: %d", r.StatusCode)
	}
	if r := postJSON(t, ts.URL+"/auth/refresh", map[string]string{"refresh_token": c.RefreshToken}); r.StatusCode != http.StatusUnauthorized {
		t.Fatalf("refresh after logout-all: want 401, got %d", r.StatusCode)
	}
	if code := getMe(t, ts.URL, c.AccessToken); code != http.StatusOK {
		t.Fatalf("stateless access token after logout-all: want 200 until expiry, got %d", code)
	}
	if code := getMe(t, ts.URL, loginPair(t, ts.URL, "session1@x.com", testPassword).AccessToken); code != http.StatusOK {
		t.Fatalf("fresh login after logout-all: %d", code)
	}
}

// With LIBRARYZ_VERIFY_SESSION_ON_ACCESS, ending a session kills its
// access tokens at once.
func TestVerifySessionOnAccessRevokesImmediately(t *testing.T) {
	deps, _ := newTestDeps(t)
	deps.VerifySessionOnAccess = true
	ts := newServer(t, deps)
	signupAndLogin(t, ts.URL, "verify-on-access@x.com", testPassword, "V")
	a := loginPair(t, ts.URL, "verify-on-access@x.com", testPassword)
	b := loginPair(t, ts.URL, "verify-on-access@x.com", testPassword)

	if r := postJSON(t, ts.URL+"/auth/logout", map[string]string{"refresh_token": a.RefreshToken}); r.StatusCode != http.StatusNoContent {
		t.Fatalf("logout: %d", r.StatusCode)
	}
	if code := getMe(t, ts.URL, a.AccessToken); code != http.StatusUnauthorized {
		t.Fatalf("access token of a logged-out session: want 401, got %d", code)
	}
	if code := getMe(t, ts.URL, b.AccessToken); code != http.StatusOK {
		t.Fatalf("other session: want 200, got %d", code)
	}
	req, _ := http.NewRequest(http.MethodPost, ts.URL+"/auth/logout-all", nil)
	req.Header.Set("Authorization", "Bearer "+b.AccessToken)
	if r, _ := http.DefaultClient.Do(req); r.StatusCode != http.StatusNoContent {
		t.Fatalf("logout-all: %d", r.StatusCode)
	}
	if code := getMe(t, ts.URL, b.AccessToken); code != http.StatusUnauthorized {
		t.Fatalf("access token after logout-all: want 401, got %d", code)
	}
}

// Presenting a spent refresh token is treated as theft: the whole session
// is revoked, including the token it was rotated into.
func TestRefreshTokenReuseRevokesSession(t *testing.T) {
	ts, _ := newTestServer(t)
	signupAndLogin(t, ts.URL, "reuse@x.com", testPassword, "R")
	a := loginPair(t, ts.URL, "reuse@x.com", testPassword)

	resp := postJSON(t, ts.URL+"/auth/refresh", map[string]string{"refresh_token": a.RefreshToken})
	var a2 tokenPair
	_ = json.NewDecoder(resp.Body).Decode(&a2)
	if resp.StatusCode != http.StatusOK || a2.RefreshToken == "" {
		t.Fatalf("refresh: %d", resp.StatusCode)
	}
	if r := postJSON(t, ts.URL+"/auth/refresh", map[string]string{"refresh_token": a.RefreshToken}); r.StatusCode != http.StatusUnauthorized {
		t.Fatalf("reused refresh token: want 401, got %d", r.StatusCode)
	}
	if r := postJSON(t, ts.URL+"/auth/refresh", map[string]string{"refresh_token": a2.RefreshToken}); r.StatusCode != http.StatusUnauthorized {
		t.Fatalf("token rotated from a reused one: want 401 (session revoked), got %d", r.StatusCode)
	}
}

// Roles are read per request (LoadSubjectOnAccess, the default): a
// promotion or demotion applies to the very next request on the same token.
func TestRoleChangesApplyImmediately(t *testing.T) {
	ts, db := newTestServer(t)
	signupAndLogin(t, ts.URL, "promote@x.com", testPassword, "P")
	tok := "Bearer " + loginPair(t, ts.URL, "promote@x.com", testPassword).AccessToken
	url := ts.URL + "/contributions/" + uuid.NewString() + "/approve"

	if r := postAuthed(t, url, tok); r.StatusCode != http.StatusForbidden {
		t.Fatalf("before promotion: want 403, got %d", r.StatusCode)
	} else if body := strings.TrimSpace(readBody(r)); body != "moderator required" {
		t.Fatalf("403 body = %q", body)
	}
	grantModerator(t, db, "promote@x.com")
	// Past the permission check: the contribution doesn't exist.
	if r := postAuthed(t, url, tok); r.StatusCode != http.StatusNotFound {
		t.Fatalf("after promotion, same token: want 404, got %d %s", r.StatusCode, readBody(r))
	}
	db.Exec(`DELETE FROM user_roles WHERE role = 'moderator' AND user_id = (SELECT id FROM users WHERE email = ?)`, "promote@x.com")
	if r := postAuthed(t, url, tok); r.StatusCode != http.StatusForbidden {
		t.Fatalf("after demotion, same token: want 403, got %d", r.StatusCode)
	}
}

// A disabled user is cut off at once: the access token stops working and
// the session can't refresh.
func TestDisabledUserIsCutOffImmediately(t *testing.T) {
	ts, db := newTestServer(t)
	signupAndLogin(t, ts.URL, "disable@x.com", testPassword, "D")
	p := loginPair(t, ts.URL, "disable@x.com", testPassword)
	db.Table("users").Where("email = ?", "disable@x.com").Update("disabled", true)

	if code := getMe(t, ts.URL, p.AccessToken); code != http.StatusUnauthorized {
		t.Fatalf("access token of a disabled user: want 401, got %d", code)
	}
	if r := postJSON(t, ts.URL+"/auth/refresh", map[string]string{"refresh_token": p.RefreshToken}); r.StatusCode != http.StatusUnauthorized {
		t.Fatalf("refresh of a disabled user: want 401, got %d", r.StatusCode)
	}
	if r := postJSON(t, ts.URL+"/auth/login", map[string]string{"email": "disable@x.com", "password": testPassword}); r.StatusCode != http.StatusUnauthorized {
		t.Fatalf("login of a disabled user: want 401, got %d", r.StatusCode)
	}
}

// With LIBRARYZ_LOAD_SUBJECT_ON_ACCESS=false roles come from the access
// token: a promotion reaches moderator routes at the next refresh.
func TestTokenRolesWithoutLoadOnAccess(t *testing.T) {
	deps, db := newTestDeps(t)
	deps.LoadSubjectOnAccess = false
	ts := newServer(t, deps)
	signupAndLogin(t, ts.URL, "tokenroles@x.com", testPassword, "T")
	p := loginPair(t, ts.URL, "tokenroles@x.com", testPassword)
	grantModerator(t, db, "tokenroles@x.com")

	url := ts.URL + "/contributions/" + uuid.NewString() + "/approve"
	if r := postAuthed(t, url, "Bearer "+p.AccessToken); r.StatusCode != http.StatusForbidden {
		t.Fatalf("token issued before promotion: want 403, got %d", r.StatusCode)
	}
	resp := postJSON(t, ts.URL+"/auth/refresh", map[string]string{"refresh_token": p.RefreshToken})
	var p2 tokenPair
	_ = json.NewDecoder(resp.Body).Decode(&p2)
	if r := postAuthed(t, url, "Bearer "+p2.AccessToken); r.StatusCode != http.StatusNotFound {
		t.Fatalf("refreshed token: want 404, got %d %s", r.StatusCode, readBody(r))
	}
}

// The Wasm client is served from another origin: its writes and its login
// must not be rejected by cross-origin protection (bearer only, no cookies).
func TestCrossOriginClientIsAccepted(t *testing.T) {
	ts, _ := newTestServer(t)
	auth := signupLogin(t, ts.URL, "wasm@x.com")
	send := func(path, auth string, body any) *http.Response {
		t.Helper()
		req, _ := http.NewRequest(http.MethodPost, ts.URL+path, mustJSON(t, body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Sec-Fetch-Site", "cross-site")
		req.Header.Set("Origin", "http://example.test")
		if auth != "" {
			req.Header.Set("Authorization", auth)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		return resp
	}
	if r := send("/works", auth, map[string]any{"title": "From the web"}); r.StatusCode != http.StatusCreated {
		t.Fatalf("cross-origin create: want 201, got %d %s", r.StatusCode, readBody(r))
	}
	if r := send("/auth/login", "", map[string]string{"email": "wasm@x.com", "password": testPassword}); r.StatusCode != http.StatusOK {
		t.Fatalf("cross-origin login: want 200, got %d", r.StatusCode)
	}
}

func TestSignupValidation(t *testing.T) {
	ts, _ := newTestServer(t)
	for _, c := range []struct {
		body map[string]string
		code int
		msg  string
	}{
		{map[string]string{"email": "not-an-email", "password": testPassword}, http.StatusBadRequest, "a valid email is required"},
		{map[string]string{"email": "short@x.com", "password": "hunter2"}, http.StatusBadRequest, "password must be at least 8 characters"},
		{map[string]string{"email": "eight@x.com", "password": "hunter22"}, http.StatusCreated, ""},
		{map[string]string{"email": "Signup-OK@X.com", "password": testPassword, "name": " Ok "}, http.StatusCreated, ""},
		{map[string]string{"email": "signup-ok@x.com", "password": testPassword}, http.StatusConflict, "email already registered"},
	} {
		r := postJSON(t, ts.URL+"/auth/signup", c.body)
		body := readBody(r)
		if r.StatusCode != c.code || (c.msg != "" && strings.TrimSpace(body) != c.msg) {
			t.Fatalf("signup %v: got %d %q, want %d %q", c.body, r.StatusCode, body, c.code, c.msg)
		}
		if c.code == http.StatusCreated && c.body["name"] != "" {
			var p tokenPair
			if err := json.Unmarshal([]byte(body), &p); err != nil || p.AccessToken == "" || p.RefreshToken == "" {
				t.Fatalf("signup should return a token pair, got %s", body)
			}
			req, _ := http.NewRequest(http.MethodGet, ts.URL+"/auth/me", nil)
			req.Header.Set("Authorization", "Bearer "+p.AccessToken)
			resp, _ := http.DefaultClient.Do(req)
			if me := readBody(resp); !strings.Contains(me, `"email":"signup-ok@x.com"`) || !strings.Contains(me, `"name":"Ok"`) {
				t.Fatalf("me after signup: %s", me)
			}
		}
	}
	// Login normalizes the email the same way.
	loginPair(t, ts.URL, "  SIGNUP-ok@x.com ", testPassword)
}

// Repeated failures for one account are throttled (iam's per-account
// limiter: 5 failures, then a growing back-off), even with the right
// password, and without touching other accounts.
func TestLoginThrottling(t *testing.T) {
	ts, _ := newTestServer(t)
	signupAndLogin(t, ts.URL, "throttle@x.com", testPassword, "T")
	signupAndLogin(t, ts.URL, "throttle-other@x.com", testPassword, "T")
	wrong := map[string]string{"email": "throttle@x.com", "password": "wrong-password-123"}
	for i := 0; i < 5; i++ {
		if r := postJSON(t, ts.URL+"/auth/login", wrong); r.StatusCode != http.StatusUnauthorized {
			t.Fatalf("failure %d: want 401, got %d", i+1, r.StatusCode)
		}
	}
	r := postJSON(t, ts.URL+"/auth/login", map[string]string{"email": "throttle@x.com", "password": testPassword})
	if r.StatusCode != http.StatusTooManyRequests || r.Header.Get("Retry-After") == "" {
		t.Fatalf("after 5 failures: want 429 with Retry-After, got %d %v", r.StatusCode, r.Header)
	}
	loginPair(t, ts.URL, "throttle-other@x.com", testPassword)
}

// The same opaque error for an unknown account and a wrong password.
func TestLoginDoesNotRevealAccounts(t *testing.T) {
	ts, _ := newTestServer(t)
	signupAndLogin(t, ts.URL, "enum@x.com", testPassword, "E")
	for _, email := range []string{"enum@x.com", "nobody-here@x.com"} {
		r := postJSON(t, ts.URL+"/auth/login", map[string]string{"email": email, "password": "wrong-password-123"})
		if body := strings.TrimSpace(readBody(r)); r.StatusCode != http.StatusUnauthorized || body != "invalid credentials" {
			t.Fatalf("login %s: %d %q", email, r.StatusCode, body)
		}
	}
}

// Malformed or foreign Authorization headers never authenticate (#8).
func TestMalformedAuthorizationHeaders(t *testing.T) {
	ts, _ := newTestServer(t)
	good := signupLogin(t, ts.URL, "headers@x.com")
	tok := strings.TrimPrefix(good, "Bearer ")
	for _, h := range []string{
		"", tok, "Basic " + tok, "Bearer", "Bearer ", "Bearer  " + tok, "Bearer " + tok + " extra",
		"xBearer " + tok, "Bearer " + tok[:len(tok)-2], "Bearer not.a.jwt",
	} {
		req, _ := http.NewRequest(http.MethodGet, ts.URL+"/auth/me", nil)
		if h != "" {
			req.Header.Set("Authorization", h)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		if resp.StatusCode != http.StatusUnauthorized {
			t.Fatalf("Authorization %q: want 401, got %d", h, resp.StatusCode)
		}
	}
	// Two Authorization headers are ambiguous.
	req, _ := http.NewRequest(http.MethodGet, ts.URL+"/auth/me", nil)
	req.Header.Add("Authorization", good)
	req.Header.Add("Authorization", good)
	if resp, _ := http.DefaultClient.Do(req); resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("duplicate Authorization headers: want 401, got %d", resp.StatusCode)
	}
	if code := getMe(t, ts.URL, tok); code != http.StatusOK {
		t.Fatalf("well-formed token: %d", code)
	}
}

func TestListAndRevokeSessions(t *testing.T) {
	ts, _ := newTestServer(t)
	signupAndLogin(t, ts.URL, "devices@x.com", testPassword, "D")
	a := loginPair(t, ts.URL, "devices@x.com", testPassword)
	other := signupLogin(t, ts.URL, "devices-other@x.com")

	list := func(access string) []map[string]any {
		t.Helper()
		req, _ := http.NewRequest(http.MethodGet, ts.URL+"/me/sessions", nil)
		req.Header.Set("Authorization", "Bearer "+access)
		resp, _ := http.DefaultClient.Do(req)
		var out []map[string]any
		if err := json.NewDecoder(resp.Body).Decode(&out); err != nil || resp.StatusCode != http.StatusOK {
			t.Fatalf("list sessions: %d %v", resp.StatusCode, err)
		}
		return out
	}
	sessions := list(a.AccessToken)
	if len(sessions) != 3 { // signup + signupAndLogin's login + a
		t.Fatalf("want 3 sessions, got %+v", sessions)
	}
	var current, previous string
	currents := 0
	for _, s := range sessions {
		if s["current"] == true {
			current = s["id"].(string)
			currents++
		} else {
			previous = s["id"].(string)
		}
	}
	if currents != 1 || previous == "" {
		t.Fatalf("exactly one session should be current: %+v", sessions)
	}

	// Someone else can't revoke it.
	if r := deleteAuthed(t, ts.URL+"/me/sessions/"+previous, other, nil); r.StatusCode != http.StatusNotFound {
		t.Fatalf("revoke another user's session: want 404, got %d", r.StatusCode)
	}
	if r := deleteAuthed(t, ts.URL+"/me/sessions/"+previous, "Bearer "+a.AccessToken, nil); r.StatusCode != http.StatusNoContent {
		t.Fatalf("revoke: want 204, got %d %s", r.StatusCode, readBody(r))
	}
	got := list(a.AccessToken)
	if len(got) != 2 || slices.ContainsFunc(got, func(s map[string]any) bool { return s["id"] == previous }) {
		t.Fatalf("after revoke: %+v", got)
	}
	if !slices.ContainsFunc(got, func(s map[string]any) bool { return s["id"] == current }) {
		t.Fatalf("current session gone after revoking another: %+v", got)
	}
	if r := deleteAuthed(t, ts.URL+"/me/sessions/"+previous, "Bearer "+a.AccessToken, nil); r.StatusCode != http.StatusNotFound {
		t.Fatalf("revoke twice: want 404, got %d", r.StatusCode)
	}
}

// Pre-iam accounts had bcrypt hashes; they still log in and are upgraded to
// argon2id on the way.
func TestBcryptHashUpgradedOnLogin(t *testing.T) {
	ts, db := newTestServer(t)
	signupAndLogin(t, ts.URL, "bcrypt@x.com", testPassword, "B")
	legacy, err := bcrypt.GenerateFromPassword([]byte(testPassword), bcrypt.MinCost)
	if err != nil {
		t.Fatal(err)
	}
	db.Table("password_credentials").Where("login = ?", "bcrypt@x.com").Update("hash", string(legacy))

	loginPair(t, ts.URL, "bcrypt@x.com", testPassword)
	var hash string
	db.Table("password_credentials").Select("hash").Where("login = ?", "bcrypt@x.com").Take(&hash)
	if !strings.HasPrefix(hash, "$argon2id$") {
		t.Fatalf("hash not upgraded: %q", hash)
	}
}

// A non-moderator may remove only a work they created that has no editions —
// what the new-work upload flow does when its first upload is rejected.
func TestCreatorCanRemoveOwnEmptyWorkOnly(t *testing.T) {
	ts, _ := newTestServer(t)
	creator := signupAndLogin(t, ts.URL, "creator1@x.com", testPassword, "C")
	other := signupAndLogin(t, ts.URL, "creator2@x.com", testPassword, "O")
	reason := map[string]string{"reason": "upload failed"}

	empty := createWork(t, ts.URL, creator, "Empty Work", "")
	if r := deleteAuthed(t, ts.URL+"/works/"+empty, other, reason); r.StatusCode != http.StatusForbidden {
		t.Fatalf("someone else's work: want 403, got %d", r.StatusCode)
	}
	if r := deleteAuthed(t, ts.URL+"/works/"+empty, creator, reason); r.StatusCode != http.StatusNoContent {
		t.Fatalf("own empty work: want 204, got %d %s", r.StatusCode, readBody(r))
	}

	withEdition := createWork(t, ts.URL, creator, "Has Edition", "")
	editionID(t, uploadEdition(t, ts.URL, creator, withEdition, "txt", "en", []byte("creator1 content\n")))
	if r := deleteAuthed(t, ts.URL+"/works/"+withEdition, creator, reason); r.StatusCode != http.StatusForbidden {
		t.Fatalf("own work with an edition: want 403, got %d", r.StatusCode)
	}
	if r := deleteAuthed(t, ts.URL+"/works/"+uuid.NewString(), creator, reason); r.StatusCode != http.StatusNotFound {
		t.Fatalf("unknown work: want 404, got %d", r.StatusCode)
	}
}
