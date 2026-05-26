package sanitize

import (
	"archive/zip"
	"bytes"
	"errors"
	"strings"
	"testing"
)

func TestValidate_AllowlistRejectsUnknown(t *testing.T) {
	cases := []string{"docx", "mobi", "azw3", "djvu", "cbz", ""}
	for _, fmt := range cases {
		t.Run("format="+fmt, func(t *testing.T) {
			err := Validate(fmt, bytes.NewReader([]byte("anything")), 8)
			if !errors.Is(err, ErrUnsupportedFormat) {
				t.Fatalf("expected ErrUnsupportedFormat, got %v", err)
			}
		})
	}
}

func TestValidate_CaseInsensitiveFormat(t *testing.T) {
	body := minimalPDF()
	for _, fmt := range []string{"pdf", "PDF", "Pdf", " pdf "} {
		t.Run("format="+fmt, func(t *testing.T) {
			if err := Validate(fmt, bytes.NewReader(body), int64(len(body))); err != nil {
				t.Fatalf("expected nil, got %v", err)
			}
		})
	}
}

func TestValidate_SeeksBackToStartOnSuccess(t *testing.T) {
	body := minimalPDF()
	rs := bytes.NewReader(body)
	// Advance the cursor so we can prove Validate resets it.
	_, _ = rs.Seek(5, 0)
	if err := Validate("pdf", rs, int64(len(body))); err != nil {
		t.Fatalf("validate: %v", err)
	}
	pos, _ := rs.Seek(0, 1)
	if pos != 0 {
		t.Fatalf("expected cursor at 0 after Validate, got %d", pos)
	}
}

func TestValidate_PDF(t *testing.T) {
	cases := []struct {
		name    string
		body    []byte
		wantErr error
	}{
		{"valid minimal", minimalPDF(), nil},
		{"valid with leading garbage", append([]byte("# wrapped\n"), minimalPDF()...), nil},
		{"random bytes", bytes.Repeat([]byte{0x00, 0xFF}, 100), ErrFormatMismatch},
		{"epub bytes claimed as pdf", minimalEPUB(t), ErrFormatMismatch},
		{"truncated — no EOF", []byte("%PDF-1.4\nbody without trailer\n"), ErrInvalidContent},
		{"bad version", []byte("%PDF-9.0\nbody\n%%EOF\n"), ErrInvalidContent},
		{"too short", []byte("%PDF-"), ErrInvalidContent},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			err := Validate("pdf", bytes.NewReader(tc.body), int64(len(tc.body)))
			if tc.wantErr == nil {
				if err != nil {
					t.Fatalf("expected nil, got %v", err)
				}
				return
			}
			if !errors.Is(err, tc.wantErr) {
				t.Fatalf("expected %v, got %v", tc.wantErr, err)
			}
		})
	}
}

func TestValidate_EPUB(t *testing.T) {
	cases := []struct {
		name    string
		build   func(t *testing.T) []byte
		wantErr error
	}{
		{"valid minimal", func(t *testing.T) []byte { return minimalEPUB(t) }, nil},
		{
			"missing mimetype first entry",
			func(t *testing.T) []byte {
				var buf bytes.Buffer
				zw := zip.NewWriter(&buf)
				w, _ := zw.Create("META-INF/container.xml")
				w.Write([]byte("<container/>"))
				zw.Close()
				return buf.Bytes()
			},
			ErrFormatMismatch,
		},
		{
			"mimetype with wrong body",
			func(t *testing.T) []byte {
				var buf bytes.Buffer
				zw := zip.NewWriter(&buf)
				h := &zip.FileHeader{Name: "mimetype", Method: zip.Store}
				w, _ := zw.CreateHeader(h)
				w.Write([]byte("text/plain"))
				zw.Close()
				return buf.Bytes()
			},
			ErrFormatMismatch,
		},
		{
			"mimetype compressed (must be stored)",
			func(t *testing.T) []byte {
				var buf bytes.Buffer
				zw := zip.NewWriter(&buf)
				h := &zip.FileHeader{Name: "mimetype", Method: zip.Deflate}
				w, _ := zw.CreateHeader(h)
				w.Write([]byte(epubMimetypeBody))
				zw.Close()
				return buf.Bytes()
			},
			ErrFormatMismatch,
		},
		{
			"not a zip",
			func(t *testing.T) []byte { return []byte("definitely not a zip file") },
			ErrFormatMismatch,
		},
		{
			"path traversal entry",
			func(t *testing.T) []byte {
				return buildEPUB(t, map[string][]byte{"../etc/passwd": []byte("evil")})
			},
			ErrInvalidContent,
		},
		{
			"absolute path entry",
			func(t *testing.T) []byte {
				return buildEPUB(t, map[string][]byte{"/etc/passwd": []byte("evil")})
			},
			ErrInvalidContent,
		},
		{
			"backslash entry",
			func(t *testing.T) []byte {
				return buildEPUB(t, map[string][]byte{`OEBPS\nested.xhtml`: []byte("x")})
			},
			ErrInvalidContent,
		},
		{
			"too many entries",
			func(t *testing.T) []byte {
				entries := make(map[string][]byte, epubMaxEntries+1)
				for i := 0; i <= epubMaxEntries; i++ {
					entries[strings.Repeat("a", 4)+itoa(i)+".xhtml"] = []byte("x")
				}
				return buildEPUB(t, entries)
			},
			ErrZipBomb,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			body := tc.build(t)
			err := Validate("epub", bytes.NewReader(body), int64(len(body)))
			if tc.wantErr == nil {
				if err != nil {
					t.Fatalf("expected nil, got %v", err)
				}
				return
			}
			if !errors.Is(err, tc.wantErr) {
				t.Fatalf("expected %v, got %v", tc.wantErr, err)
			}
		})
	}
}

func TestValidate_EPUBZipBombByRatio(t *testing.T) {
	// Craft an entry where uncompressed >> compressed. We can't easily
	// produce a real high-ratio deflate stream in-test, so we forge the
	// central-directory size fields by writing a real zip then patching
	// the UncompressedSize64 reading via the archive/zip path is read-only.
	// Instead: use the FileHeader API to set UncompressedSize64 directly
	// — zip.Writer respects it for STORE entries.
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	// mimetype first (so it doesn't trip the mimetype check)
	mh := &zip.FileHeader{Name: "mimetype", Method: zip.Store}
	mw, _ := zw.CreateHeader(mh)
	mw.Write([]byte(epubMimetypeBody))
	// Bomb entry: tiny on-disk, claims huge uncompressed size
	zw.Close()

	// Read it back and patch the second entry's UncompressedSize via a
	// hand-crafted replacement. Simpler approach: build the bomb without
	// archive/zip's normal flow — produce a synthetic zip where one entry
	// reports ratio > epubMaxRatio.
	bombed := craftRatioBomb(t)
	err := Validate("epub", bytes.NewReader(bombed), int64(len(bombed)))
	if !errors.Is(err, ErrZipBomb) {
		t.Fatalf("expected ErrZipBomb, got %v", err)
	}
}

func TestValidate_TXT(t *testing.T) {
	cases := []struct {
		name    string
		body    []byte
		wantErr error
	}{
		{"ascii", []byte("Hello, world.\n"), nil},
		{"utf8 multibyte", []byte("こんにちは世界 — café 🌍\n"), nil},
		{"empty", []byte(""), nil},
		{
			"invalid utf8",
			// 0xC3 0x28 is an invalid 2-byte sequence (0x28 isn't a continuation).
			[]byte("abc\xC3\x28def"),
			ErrInvalidContent,
		},
		{
			"lone continuation byte",
			[]byte("\x80\x80\x80"),
			ErrInvalidContent,
		},
		{
			"truncated multibyte at EOF",
			// 0xE2 starts a 3-byte sequence but no continuation follows.
			[]byte("text \xE2"),
			ErrInvalidContent,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			err := Validate("txt", bytes.NewReader(tc.body), int64(len(tc.body)))
			if tc.wantErr == nil {
				if err != nil {
					t.Fatalf("expected nil, got %v", err)
				}
				return
			}
			if !errors.Is(err, tc.wantErr) {
				t.Fatalf("expected %v, got %v", tc.wantErr, err)
			}
		})
	}
}

func TestValidate_TXTOversize(t *testing.T) {
	// Don't allocate 100 MiB to test this — fake the size. The reader
	// won't actually be consumed because the size check fires first.
	err := Validate("txt", bytes.NewReader(nil), txtMaxSize+1)
	if !errors.Is(err, ErrInvalidContent) {
		t.Fatalf("expected ErrInvalidContent, got %v", err)
	}
}

// ---- fixture helpers ----

func minimalPDF() []byte {
	// Just enough to satisfy the header + EOF checks. The bytes between
	// don't need to parse as a real PDF object graph — L1 doesn't open it.
	return []byte("%PDF-1.4\n1 0 obj\n<<>>\nendobj\nxref\ntrailer\n<<>>\n%%EOF\n")
}

func minimalEPUB(t *testing.T) []byte {
	t.Helper()
	return buildEPUB(t, map[string][]byte{
		"META-INF/container.xml": []byte(`<?xml version="1.0"?><container/>`),
		"OEBPS/content.opf":      []byte(`<?xml version="1.0"?><package/>`),
	})
}

// buildEPUB writes a zip whose first entry is a stored "mimetype" with the
// canonical EPUB body, followed by extras in arbitrary order.
func buildEPUB(t *testing.T, extras map[string][]byte) []byte {
	t.Helper()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	h := &zip.FileHeader{Name: "mimetype", Method: zip.Store}
	w, err := zw.CreateHeader(h)
	if err != nil {
		t.Fatalf("create mimetype: %v", err)
	}
	if _, err := w.Write([]byte(epubMimetypeBody)); err != nil {
		t.Fatalf("write mimetype: %v", err)
	}
	for name, body := range extras {
		fw, err := zw.Create(name)
		if err != nil {
			t.Fatalf("create %q: %v", name, err)
		}
		if _, err := fw.Write(body); err != nil {
			t.Fatalf("write %q: %v", name, err)
		}
	}
	if err := zw.Close(); err != nil {
		t.Fatalf("zip close: %v", err)
	}
	return buf.Bytes()
}

// craftRatioBomb builds a zip whose second entry, read back through
// archive/zip, reports a compression ratio > epubMaxRatio. We achieve this
// by writing a real Deflate entry of highly redundant data — Deflate
// achieves ~1000:1 on repetition.
func craftRatioBomb(t *testing.T) []byte {
	t.Helper()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	mh := &zip.FileHeader{Name: "mimetype", Method: zip.Store}
	mw, err := zw.CreateHeader(mh)
	if err != nil {
		t.Fatalf("mimetype: %v", err)
	}
	mw.Write([]byte(epubMimetypeBody))

	bh := &zip.FileHeader{Name: "bomb.txt", Method: zip.Deflate}
	bw, err := zw.CreateHeader(bh)
	if err != nil {
		t.Fatalf("bomb header: %v", err)
	}
	// 4 MiB of zeros compresses to ~4 KiB — ratio ~1000:1. Push above
	// the threshold by using 8 MiB.
	zeros := make([]byte, 8<<20)
	if _, err := bw.Write(zeros); err != nil {
		t.Fatalf("bomb write: %v", err)
	}
	if err := zw.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}
	return buf.Bytes()
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b [20]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	return string(b[i:])
}
