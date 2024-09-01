package server_test

import (
	"bytes"
	"encoding/json"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/glebarez/sqlite"
	"github.com/kararnab/libraryZ/internal/server"
	"github.com/kararnab/libraryZ/internal/storage"
	"gorm.io/gorm"
)

func newTestServer(t *testing.T) *httptest.Server {
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

	ts := httptest.NewServer(server.New(server.Deps{
		DB:             db,
		Storage:        store,
		MaxUploadBytes: 16 << 20,
	}))
	t.Cleanup(ts.Close)
	return ts
}

func TestSmokeHappyPath(t *testing.T) {
	ts := newTestServer(t)

	// Health
	if resp, err := http.Get(ts.URL + "/health"); err != nil || resp.StatusCode != 200 {
		t.Fatalf("health: err=%v code=%d", err, statusOf(resp))
	}

	// Signup
	signup := mustJSON(t, map[string]string{"email": "a@b.com", "password": "hunter2", "name": "Test"})
	if resp, err := http.Post(ts.URL+"/auth/signup", "application/json", signup); err != nil || resp.StatusCode != 201 {
		t.Fatalf("signup: err=%v code=%d", err, statusOf(resp))
	}

	// Login -> Authorization header
	login := mustJSON(t, map[string]string{"email": "a@b.com", "password": "hunter2"})
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

	// Upload edition (multipart)
	fileBytes := []byte("%PDF-1.4 fake pdf body for the smoke test\n")
	editionResp := uploadEdition(t, ts.URL, auth, work.ID, "pdf", "en", fileBytes)
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
	dupResp := uploadEdition(t, ts.URL, auth, work.ID, "pdf", "en", fileBytes)
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
	ts := newTestServer(t)

	body := mustJSON(t, map[string]string{"title": "x"})
	resp, err := http.Post(ts.URL+"/works", "application/json", body)
	if err != nil {
		t.Fatalf("post: %v", err)
	}
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", resp.StatusCode)
	}
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
