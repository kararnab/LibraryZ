package server_test

import (
	"bytes"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"testing"
	"time"

	"github.com/kararnab/libraryZ/internal/sanitize"
	"github.com/kararnab/libraryZ/internal/sanitize/sanitizetest"
	"github.com/pdfcpu/pdfcpu/pkg/api"
	"github.com/pdfcpu/pdfcpu/pkg/pdfcpu"
	"github.com/pdfcpu/pdfcpu/pkg/pdfcpu/model"
	"github.com/pdfcpu/pdfcpu/pkg/pdfcpu/types"
)

// Every PDF upload in this package goes through the out-of-process
// sanitizer, the same way cmd/libraryz runs it. The test binary doubles as
// the child.
func TestMain(m *testing.M) {
	sanitize.RunChildIfRequested()
	if err := sanitize.EnableIsolation(sanitize.IsolationConfig{
		MemoryLimit:   256 << 20,
		Timeout:       time.Minute,
		MaxConcurrent: 2,
	}); err != nil {
		log.Fatalf("enable sanitize isolation: %v", err)
	}
	os.Exit(m.Run())
}

// A ~300 KiB PDF whose object stream inflates to 256 MiB is refused with 413
// and nothing is recorded; the server process never parses it.
func TestUploadPDFDecompressionBombReturns413(t *testing.T) {
	ts, _ := newTestServer(t)
	auth := signupLogin(t, ts.URL, "pdf-bomb@b.com")
	_, w := postWork(t, ts.URL, auth, map[string]any{"title": "Bomb"})
	workID := w["id"].(string)

	resp := uploadEdition(t, ts.URL, auth, workID, "pdf", "en", sanitizetest.ObjStmBombPDF(256<<20))
	if resp.StatusCode != http.StatusRequestEntityTooLarge {
		t.Fatalf("want 413, got %d %s", resp.StatusCode, readBody(resp))
	}

	got, _ := http.Get(ts.URL + "/works/" + workID)
	var work struct {
		Editions []struct{ ID string } `json:"editions"`
	}
	_ = json.NewDecoder(got.Body).Decode(&work)
	if len(work.Editions) != 0 {
		t.Fatalf("rejected upload recorded editions: %+v", work.Editions)
	}
}

// Sanitizing a PDF re-serializes it with a fresh timestamp and file ID, so
// the stored bytes differ on every upload. Re-uploading the same PDF must
// still be detected as a duplicate (keyed on the uploaded bytes' hash).
func TestUploadDuplicatePDFReturns409(t *testing.T) {
	ts, _ := newTestServer(t)
	auth := signupLogin(t, ts.URL, "dup-pdf@b.com")
	_, a := postWork(t, ts.URL, auth, map[string]any{"title": "PDF A"})
	_, b := postWork(t, ts.URL, auth, map[string]any{"title": "PDF B"})
	aID, bID := a["id"].(string), b["id"].(string)

	pdf := buildTestPDF(t)
	first := uploadEdition(t, ts.URL, auth, aID, "pdf", "en", pdf)
	if first.StatusCode != http.StatusCreated {
		t.Fatalf("first upload: want 201, got %d %s", first.StatusCode, readBody(first))
	}
	var ed struct{ ID string }
	_ = json.NewDecoder(first.Body).Decode(&ed)

	for _, workID := range []string{aID, bID} {
		resp := uploadEdition(t, ts.URL, auth, workID, "pdf", "en", pdf)
		if resp.StatusCode != http.StatusConflict {
			t.Fatalf("re-upload to %s: want 409, got %d %s", workID, resp.StatusCode, readBody(resp))
		}
		var dup struct {
			EditionID string `json:"edition_id"`
		}
		_ = json.NewDecoder(resp.Body).Decode(&dup)
		if dup.EditionID != ed.ID {
			t.Fatalf("409 names edition %q, want %q", dup.EditionID, ed.ID)
		}
	}
}

// buildTestPDF returns a minimal one-page PDF that passes sanitization.
func buildTestPDF(t *testing.T) []byte {
	t.Helper()
	conf := model.NewDefaultConfiguration()
	conf.ValidationMode = model.ValidationRelaxed
	ctx, err := pdfcpu.CreateContextWithXRefTable(conf, types.PaperSize["A4"])
	if err != nil {
		t.Fatalf("create pdf: %v", err)
	}
	var buf bytes.Buffer
	if err := api.WriteContext(ctx, &buf); err != nil {
		t.Fatalf("write pdf: %v", err)
	}
	return buf.Bytes()
}
