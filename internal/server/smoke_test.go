package server_test

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strings"
	"testing"

	"github.com/glebarez/sqlite"
	"github.com/google/uuid"
	"github.com/kararnab/libraryZ/internal/recommendation"
	"github.com/kararnab/libraryZ/internal/server"
	"github.com/kararnab/libraryZ/internal/storage"
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

	return server.Deps{DB: db, Storage: store, MaxUploadBytes: 16 << 20}, db
}

func newTestServer(t *testing.T) (*httptest.Server, *gorm.DB) {
	t.Helper()
	deps, db := newTestDeps(t)
	// Exercise allowlist mode explicitly; the CORS tests use this origin.
	deps.AllowedOrigins = []string{"http://example.test"}
	ts := httptest.NewServer(server.New(deps))
	t.Cleanup(ts.Close)
	return ts, db
}

// promoteModerator flips is_moderator on the user with the given email.
// Mirrors the v0 production path ("update via psql") for tests that need
// approve/reject to succeed.
func promoteModerator(t *testing.T, db *gorm.DB, email string) {
	t.Helper()
	res := db.Table("users").Where("email = ?", email).Update("is_moderator", true)
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
	signup := mustJSON(t, map[string]string{"email": "a@b.com", "password": "hunter22", "name": "Test"})
	if resp, err := http.Post(ts.URL+"/auth/signup", "application/json", signup); err != nil || resp.StatusCode != 201 {
		t.Fatalf("signup: err=%v code=%d", err, statusOf(resp))
	}

	// Login -> Authorization header
	login := mustJSON(t, map[string]string{"email": "a@b.com", "password": "hunter22"})
	loginResp, err := http.Post(ts.URL+"/auth/login", "application/json", login)
	if err != nil || loginResp.StatusCode != 200 {
		t.Fatalf("login: err=%v code=%d", err, statusOf(loginResp))
	}
	auth := loginResp.Header.Get("Authorization")
	if !strings.HasPrefix(auth, "Bearer ") {
		t.Fatalf("login: missing Bearer token, got %q", auth)
	}

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

	// Dedup: re-upload identical bytes -> same edition id, 201 still
	dupResp := uploadEdition(t, ts.URL, auth, work.ID, "txt", "en", fileBytes)
	var dup struct {
		ID string `json:"id"`
	}
	_ = json.NewDecoder(dupResp.Body).Decode(&dup)
	if dup.ID != ed.ID {
		t.Fatalf("dedup failed: first=%s second=%s", ed.ID, dup.ID)
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
		"email": "cors@example.com", "password": "hunter22", "name": "C",
	})
	if r, err := http.Post(ts.URL+"/auth/signup", "application/json", signup); err != nil ||
		r.StatusCode != http.StatusCreated {
		t.Fatalf("signup: err=%v code=%d", err, statusOf(r))
	}

	login := mustJSON(t, map[string]string{"email": "cors@example.com", "password": "hunter22"})
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
	tsOff := httptest.NewServer(server.New(off))
	t.Cleanup(tsOff.Close)
	if got := preflightAllowOrigin(t, tsOff.URL, "http://localhost:3000"); got != "http://localhost:3000" {
		t.Fatalf("localhost should be allowed in dev mode, got %q", got)
	}
	if got := preflightAllowOrigin(t, tsOff.URL, lan); got != "" {
		t.Fatalf("private LAN reflected without opt-in: %q", got)
	}

	// Flag on: private LAN now allowed.
	on, _ := newTestDeps(t)
	on.AllowPrivateLAN = true
	tsOn := httptest.NewServer(server.New(on))
	t.Cleanup(tsOn.Close)
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
	auth := signupAndLogin(t, ts.URL, "contrib1@x.com", "hunter22", "C1")
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
	auth := signupAndLogin(t, ts.URL, "contrib2@x.com", "hunter22", "C2")
	promoteModerator(t, db, "contrib2@x.com")
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
	auth := signupAndLogin(t, ts.URL, "contrib3@x.com", "hunter22", "C3")
	promoteModerator(t, db, "contrib3@x.com")
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
	auth := signupAndLogin(t, ts.URL, "contrib4@x.com", "hunter22", "C4")
	promoteModerator(t, db, "contrib4@x.com")
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

func TestContributionInvalidPatchFieldsAreIgnored(t *testing.T) {
	ts, db := newTestServer(t)
	auth := signupAndLogin(t, ts.URL, "contrib5@x.com", "hunter22", "C5")
	promoteModerator(t, db, "contrib5@x.com")
	workID := createWork(t, ts.URL, auth, "Title A", "")

	resp := submitContribution(t, ts.URL, auth, workID, map[string]any{
		"title": "Real Update",
		"bogus": "junk-string-that-should-not-leak",
	})
	var c struct {
		ID string `json:"id"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&c)

	if r := postAuthed(t, ts.URL+"/contributions/"+c.ID+"/approve", auth); r.StatusCode != http.StatusOK {
		t.Fatalf("approve: code=%d body=%s", r.StatusCode, readBody(r))
	}

	workResp, _ := http.Get(ts.URL + "/works/" + workID)
	body := readBody(workResp)
	if !strings.Contains(body, "Real Update") {
		t.Fatalf("whitelisted title not applied: %s", body)
	}
	if strings.Contains(body, "junk-string-that-should-not-leak") {
		t.Fatalf("non-whitelisted field leaked into work: %s", body)
	}
}

// --- Moderator gate (2.2) --------------------------------------------------

func TestModeratorRequiredForApprove(t *testing.T) {
	ts, _ := newTestServer(t)
	auth := signupAndLogin(t, ts.URL, "nonmod1@x.com", "hunter22", "NM1")
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
	auth := signupAndLogin(t, ts.URL, "nonmod2@x.com", "hunter22", "NM2")
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
	auth := signupAndLogin(t, ts.URL, "meendpoint@x.com", "hunter22", "Me Tester")

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
	promoteModerator(t, db, "meendpoint@x.com")
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
	auth := signupAndLogin(t, ts.URL, "search1@x.com", "hunter22", "S1")

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
	auth := signupAndLogin(t, ts.URL, "search2@x.com", "hunter22", "S2")
	createWorkFull(t, ts.URL, auth, "Foo", "", "")

	got := searchTitles(t, ts.URL, "zzz-not-a-word")
	if len(got) != 0 {
		t.Fatalf("expected empty result for unmatched query, got %v", got)
	}
}

func TestSearchWorksOrderedByCreatedAtDesc(t *testing.T) {
	ts, _ := newTestServer(t)
	auth := signupAndLogin(t, ts.URL, "search3@x.com", "hunter22", "S3")

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

func TestLibraryUpsertRequiresAuth(t *testing.T) {
	ts, _ := newTestServer(t)
	auth := signupAndLogin(t, ts.URL, "lib0@x.com", "hunter22", "L0")
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
	auth := signupAndLogin(t, ts.URL, "lib1@x.com", "hunter22", "L1")
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
	auth := signupAndLogin(t, ts.URL, "lib2@x.com", "hunter22", "L2")
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
	auth := signupAndLogin(t, ts.URL, "lib3@x.com", "hunter22", "L3")
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
	auth := signupAndLogin(t, ts.URL, "lib4@x.com", "hunter22", "L4")
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
	auth := signupAndLogin(t, ts.URL, "rec@x.com", "hunter22", "Rec")

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
	// First signup → user id 1; that's the user we fetch recommendations for.
	auth := signupAndLogin(t, ts.URL, "mfmain@x.com", "hunter22", "Main")

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

	// Synthetic co-users establish the collaborative pattern: 2–7 like all of
	// A, 8–13 like all of B. Seeded directly (the trainer only reads user_books).
	for u := uint(2); u <= 7; u++ {
		for _, w := range a {
			seedUserBook(t, db, u, w, "read", 5)
		}
	}
	for u := uint(8); u <= 13; u++ {
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
	auth := signupAndLogin(t, ts.URL, "dismiss@x.com", "hunter22", "D")
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
	login := mustJSON(t, map[string]string{"email": email, "password": password})
	resp, err := http.Post(base+"/auth/login", "application/json", login)
	if err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("login %s: err=%v code=%d", email, err, statusOf(resp))
	}
	auth := resp.Header.Get("Authorization")
	if !strings.HasPrefix(auth, "Bearer ") {
		t.Fatalf("login %s: no Bearer token, got %q", email, auth)
	}
	return auth
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
