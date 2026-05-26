package sanitize

import (
	"archive/zip"
	"bytes"
	"errors"
	"io"
	"strings"
	"testing"

	"github.com/pdfcpu/pdfcpu/pkg/api"
	"github.com/pdfcpu/pdfcpu/pkg/pdfcpu"
	"github.com/pdfcpu/pdfcpu/pkg/pdfcpu/model"
	"github.com/pdfcpu/pdfcpu/pkg/pdfcpu/types"
)

func TestSanitize_TXTPassthrough(t *testing.T) {
	body := []byte("hello world\n")
	rs := bytes.NewReader(body)
	clean, err := Sanitize("txt", rs, int64(len(body)))
	if err != nil {
		t.Fatalf("sanitize: %v", err)
	}
	got, err := io.ReadAll(clean)
	if err != nil {
		t.Fatalf("read clean: %v", err)
	}
	if !bytes.Equal(got, body) {
		t.Fatalf("TXT not passthrough: got %q want %q", got, body)
	}
}

func TestSanitize_PDFRoundTripsCleanFile(t *testing.T) {
	body := buildPDF(t, nil)
	clean, err := Sanitize("pdf", bytes.NewReader(body), int64(len(body)))
	if err != nil {
		t.Fatalf("sanitize: %v", err)
	}
	out := mustReadAll(t, clean)
	if !bytes.HasPrefix(out, []byte("%PDF-")) {
		t.Fatalf("sanitized output is not a PDF: starts with %q", out[:min(16, len(out))])
	}
	// Reparse to confirm pdfcpu can read what it wrote.
	if _, err := api.ReadContext(bytes.NewReader(out), pdfConf()); err != nil {
		t.Fatalf("sanitized PDF unreadable: %v", err)
	}
}

func TestSanitize_PDFStripsDangerousKeys(t *testing.T) {
	cases := []struct {
		name string
		key  string
		val  types.Object
	}{
		{"JavaScript on catalog", "JavaScript", types.StringLiteral("app.alert('owned')")},
		{"JS on catalog", "JS", types.StringLiteral("app.alert('owned')")},
		{"OpenAction on catalog", "OpenAction", types.Dict{"S": types.Name("Launch")}},
		{"AA on catalog", "AA", types.Dict{"O": types.Dict{"S": types.Name("JavaScript")}}},
		{"Launch on catalog", "Launch", types.StringLiteral("/bin/sh")},
		{"EmbeddedFiles on catalog", "EmbeddedFiles", types.Dict{"Names": types.Array{}}},
		{"RichMedia on catalog", "RichMedia", types.Dict{}},
		{"XFA on catalog", "XFA", types.Array{types.StringLiteral("<xdp/>")}},
		{"SubmitForm on catalog", "SubmitForm", types.Dict{}},
		{"ImportData on catalog", "ImportData", types.Dict{}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			body := buildPDF(t, map[string]types.Object{tc.key: tc.val})
			// Sanity: the dangerous key really is present pre-sanitize.
			if !pdfCatalogHas(t, body, tc.key) {
				t.Fatalf("fixture is missing %q before sanitize", tc.key)
			}
			clean, err := Sanitize("pdf", bytes.NewReader(body), int64(len(body)))
			if err != nil {
				t.Fatalf("sanitize: %v", err)
			}
			out := mustReadAll(t, clean)
			if pdfCatalogHas(t, out, tc.key) {
				t.Fatalf("post-sanitize PDF still has dangerous key %q", tc.key)
			}
		})
	}
}

func TestSanitize_PDFStripsNestedKey(t *testing.T) {
	// /Names is a legitimate catalog entry whose value is itself a dict
	// that may carry /JavaScript (document-level scripts) or /EmbeddedFiles.
	// Verify the nested key is removed even though it's not at the
	// catalog's top level.
	names := types.Dict{
		"JavaScript":    types.Dict{"Names": types.Array{}},
		"EmbeddedFiles": types.Dict{"Names": types.Array{}},
		"Dests":         types.Dict{"Names": types.Array{}}, // legit, must be kept
	}
	body := buildPDF(t, map[string]types.Object{"Names": names})

	if !pdfNamesHas(t, body, "JavaScript") {
		t.Fatalf("fixture missing /Names/JavaScript")
	}

	clean, err := Sanitize("pdf", bytes.NewReader(body), int64(len(body)))
	if err != nil {
		t.Fatalf("sanitize: %v", err)
	}
	out := mustReadAll(t, clean)

	if pdfNamesHas(t, out, "JavaScript") {
		t.Fatalf("post-sanitize PDF still has /Names/JavaScript")
	}
	if pdfNamesHas(t, out, "EmbeddedFiles") {
		t.Fatalf("post-sanitize PDF still has /Names/EmbeddedFiles")
	}
	if !pdfNamesHas(t, out, "Dests") {
		t.Fatalf("post-sanitize PDF lost legitimate /Names/Dests")
	}
}

func TestSanitize_PDFInvalidContent(t *testing.T) {
	// Bytes that pass L1 magic+EOF but can't be parsed as a PDF object graph.
	body := []byte("%PDF-1.4\n% no objects here\n%%EOF\n")
	_, err := Sanitize("pdf", bytes.NewReader(body), int64(len(body)))
	if !errors.Is(err, ErrInvalidContent) {
		t.Fatalf("expected ErrInvalidContent, got %v", err)
	}
}

func TestSanitize_EPUBClean(t *testing.T) {
	body := buildEPUB(t, map[string][]byte{
		"OEBPS/chapter1.xhtml": []byte(`<?xml version="1.0"?><html><body><p>Hello.</p><a href="https://example.com">link</a></body></html>`),
	})
	_, err := Sanitize("epub", bytes.NewReader(body), int64(len(body)))
	if err != nil {
		t.Fatalf("clean EPUB rejected: %v", err)
	}
}

func TestSanitize_EPUBActiveContent(t *testing.T) {
	cases := []struct {
		name    string
		entries map[string][]byte
	}{
		{
			"script tag in xhtml",
			map[string][]byte{
				"OEBPS/c.xhtml": []byte(`<html><body><script>alert(1)</script></body></html>`),
			},
		},
		{
			"on-attribute in xhtml",
			map[string][]byte{
				"OEBPS/c.xhtml": []byte(`<html><body onload="evil()"><p>hi</p></body></html>`),
			},
		},
		{
			"onclick on element",
			map[string][]byte{
				"OEBPS/c.xhtml": []byte(`<html><body><a href="#" onclick="evil()">click</a></body></html>`),
			},
		},
		{
			"javascript: href",
			map[string][]byte{
				"OEBPS/c.xhtml": []byte(`<html><body><a href="javascript:alert(1)">go</a></body></html>`),
			},
		},
		{
			"javascript: with whitespace and casing",
			map[string][]byte{
				"OEBPS/c.xhtml": []byte(`<html><body><a href="   JavaScript:alert(1)">go</a></body></html>`),
			},
		},
		{
			"js file in archive",
			map[string][]byte{
				"OEBPS/c.xhtml": []byte(`<html><body>clean</body></html>`),
				"OEBPS/app.js":  []byte(`alert(1)`),
			},
		},
		{
			"mjs file in archive",
			map[string][]byte{
				"OEBPS/c.xhtml": []byte(`<html><body>clean</body></html>`),
				"OEBPS/mod.mjs": []byte(`export {}`),
			},
		},
		{
			"opf declares text/javascript item",
			map[string][]byte{
				"content.opf": []byte(`<?xml version="1.0"?><package>
                    <manifest>
                      <item href="c.xhtml" media-type="application/xhtml+xml"/>
                      <item href="x.es" media-type="text/javascript"/>
                    </manifest>
                  </package>`),
				"OEBPS/c.xhtml": []byte(`<html><body>clean</body></html>`),
			},
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			body := buildEPUB(t, tc.entries)
			_, err := Sanitize("epub", bytes.NewReader(body), int64(len(body)))
			if !errors.Is(err, ErrActiveContent) {
				t.Fatalf("expected ErrActiveContent, got %v", err)
			}
		})
	}
}

// ---- helpers ----

func pdfConf() *model.Configuration {
	c := model.NewDefaultConfiguration()
	c.ValidationMode = model.ValidationRelaxed
	return c
}

// buildPDF generates a minimal valid PDF and optionally injects extra keys
// into the catalog. The extra keys let us stage "dirty" fixtures with
// /JavaScript / /OpenAction / etc. without hand-rolling PDF bytes.
func buildPDF(t *testing.T, extraCatalogKeys map[string]types.Object) []byte {
	t.Helper()
	conf := pdfConf()
	ctx, err := pdfcpu.CreateContextWithXRefTable(conf, types.PaperSize["A4"])
	if err != nil {
		t.Fatalf("CreateContextWithXRefTable: %v", err)
	}
	if extraCatalogKeys != nil {
		root, err := ctx.XRefTable.Catalog()
		if err != nil {
			t.Fatalf("Catalog: %v", err)
		}
		for k, v := range extraCatalogKeys {
			root.Insert(k, v)
		}
	}
	var buf bytes.Buffer
	if err := api.WriteContext(ctx, &buf); err != nil {
		t.Fatalf("WriteContext: %v", err)
	}
	return buf.Bytes()
}

// pdfCatalogHas reports whether the PDF's catalog (RootDict) has key.
func pdfCatalogHas(t *testing.T, body []byte, key string) bool {
	t.Helper()
	ctx, err := api.ReadContext(bytes.NewReader(body), pdfConf())
	if err != nil {
		t.Fatalf("ReadContext: %v", err)
	}
	root, err := ctx.XRefTable.Catalog()
	if err != nil {
		t.Fatalf("Catalog: %v", err)
	}
	_, has := root.Find(key)
	return has
}

// pdfNamesHas reports whether the catalog's /Names sub-dict has key.
func pdfNamesHas(t *testing.T, body []byte, key string) bool {
	t.Helper()
	ctx, err := api.ReadContext(bytes.NewReader(body), pdfConf())
	if err != nil {
		t.Fatalf("ReadContext: %v", err)
	}
	root, err := ctx.XRefTable.Catalog()
	if err != nil {
		t.Fatalf("Catalog: %v", err)
	}
	namesObj, has := root.Find("Names")
	if !has {
		return false
	}
	// /Names may be inline or indirect. Resolve.
	switch n := namesObj.(type) {
	case types.Dict:
		_, ok := n.Find(key)
		return ok
	case types.IndirectRef:
		d, err := ctx.XRefTable.DereferenceDict(n)
		if err != nil {
			t.Fatalf("deref Names: %v", err)
		}
		_, ok := d.Find(key)
		return ok
	}
	return false
}

func mustReadAll(t *testing.T, r io.Reader) []byte {
	t.Helper()
	b, err := io.ReadAll(r)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	return b
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

// silence unused import warnings if a test gets removed
var _ = strings.HasPrefix
var _ = zip.Store
