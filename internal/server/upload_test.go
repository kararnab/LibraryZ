package server_test

import (
	"bytes"
	"encoding/json"
	"net/http"
	"testing"

	"github.com/pdfcpu/pdfcpu/pkg/api"
	"github.com/pdfcpu/pdfcpu/pkg/pdfcpu"
	"github.com/pdfcpu/pdfcpu/pkg/pdfcpu/model"
	"github.com/pdfcpu/pdfcpu/pkg/pdfcpu/types"
)

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
