package sanitize

import (
	"bytes"
	"context"
	"errors"
	"os"
	"testing"
	"time"

	"github.com/kararnab/libraryZ/internal/sanitize/sanitizetest"
	"github.com/pdfcpu/pdfcpu/pkg/api"
	"github.com/pdfcpu/pdfcpu/pkg/pdfcpu/types"
)

// The isolated path re-runs this test binary in child mode.
func TestMain(m *testing.M) {
	RunChildIfRequested()
	os.Exit(m.Run())
}

func enableIsolation(t *testing.T, cfg IsolationConfig) {
	t.Helper()
	if err := EnableIsolation(cfg); err != nil {
		t.Fatalf("EnableIsolation: %v", err)
	}
	t.Cleanup(DisableIsolation)
}

func TestIsolatedPDFStripsActiveContent(t *testing.T) {
	enableIsolation(t, IsolationConfig{MemoryLimit: 256 << 20, Timeout: time.Minute})

	body := buildPDF(t, map[string]types.Object{"OpenAction": types.Dict{"S": types.Name("JavaScript")}})
	rc, err := SanitizeContext(context.Background(), "pdf", bytes.NewReader(body), int64(len(body)))
	if err != nil {
		t.Fatalf("SanitizeContext: %v", err)
	}
	out := mustReadAll(t, rc)
	if _, err := api.ReadContext(context.Background(), bytes.NewReader(out), pdfConf()); err != nil {
		t.Fatalf("sanitized PDF unreadable: %v", err)
	}
	if pdfCatalogHas(t, out, "OpenAction") {
		t.Fatalf("child left /OpenAction in the catalog")
	}

	tmp := rc.(*removeOnClose).Name()
	if err := rc.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	if _, err := os.Stat(tmp); !os.IsNotExist(err) {
		t.Fatalf("temp output %s not removed on Close (stat err %v)", tmp, err)
	}
}

// A ~300 KiB PDF whose object stream inflates to 256 MiB costs pdfcpu about
// 1 GiB of heap. Under isolation only the child hits its budget, and the
// upload fails with ErrTooComplex instead of taking the server down.
func TestIsolatedPDFDecompressionBombRejected(t *testing.T) {
	enableIsolation(t, IsolationConfig{MemoryLimit: 256 << 20, Timeout: time.Minute})

	body := sanitizetest.ObjStmBombPDF(256 << 20)
	if err := Validate("pdf", bytes.NewReader(body), int64(len(body))); err != nil {
		t.Fatalf("bomb should pass L1 validation: %v", err)
	}
	_, err := SanitizeContext(context.Background(), "pdf", bytes.NewReader(body), int64(len(body)))
	if !errors.Is(err, ErrTooComplex) {
		t.Fatalf("want ErrTooComplex, got %v", err)
	}
}

func TestIsolatedPDFInvalidContent(t *testing.T) {
	enableIsolation(t, IsolationConfig{MemoryLimit: 256 << 20, Timeout: time.Minute})

	body := []byte("%PDF-1.4\n% no objects here\n%%EOF\n")
	_, err := SanitizeContext(context.Background(), "pdf", bytes.NewReader(body), int64(len(body)))
	if !errors.Is(err, ErrInvalidContent) {
		t.Fatalf("want ErrInvalidContent across the process boundary, got %v", err)
	}
}

// With every slot taken, a request gives up when its timeout expires rather
// than queueing forever.
func TestIsolatedPDFWaitsForSlotThenTimesOut(t *testing.T) {
	enableIsolation(t, IsolationConfig{MaxConcurrent: 1, Timeout: 50 * time.Millisecond})
	iso := currentIsolation()
	iso.slots <- struct{}{} // occupy the only slot
	defer func() { <-iso.slots }()

	body := buildPDF(t, nil)
	_, err := SanitizeContext(context.Background(), "pdf", bytes.NewReader(body), int64(len(body)))
	if !errors.Is(err, ErrTooComplex) {
		t.Fatalf("want ErrTooComplex after waiting for a slot, got %v", err)
	}
}

// Non-PDF formats never leave the process.
func TestSanitizeContextPassesThroughTXT(t *testing.T) {
	enableIsolation(t, IsolationConfig{MemoryLimit: 1, Timeout: time.Nanosecond})

	body := []byte("hello\n")
	rc, err := SanitizeContext(context.Background(), "txt", bytes.NewReader(body), int64(len(body)))
	if err != nil {
		t.Fatalf("SanitizeContext: %v", err)
	}
	defer rc.Close()
	if got := mustReadAll(t, rc); !bytes.Equal(got, body) {
		t.Fatalf("TXT not passed through: %q", got)
	}
}
