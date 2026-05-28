package sanitize

import (
	"bytes"
	"fmt"
	"io"
)

// PDF signature: %PDF-<major>.<minor> per ISO 32000-1 §7.5.2. The header may
// be preceded by up to a few bytes of garbage (some tools wrap PDFs in a
// shell wrapper) — Adobe's reader scans the first 1024 bytes for it, and we
// do the same so legitimate-but-quirky uploads aren't rejected.
//
// We require an EOF marker (%%EOF) somewhere in the last 1024 bytes as
// a cheap "this file isn't truncated" check. ISO 32000 §7.5.5 says the
// final line must be %%EOF, but in practice many writers append trailing
// whitespace, so we just look for it anywhere in the tail.
const (
	pdfHeaderScanLen = 1024
	pdfTrailerLen    = 1024
)

var (
	pdfMagic = []byte("%PDF-")
	pdfEOF   = []byte("%%EOF")
)

func validatePDF(rs io.ReadSeeker, size int64) error {
	// Header check — read up to pdfHeaderScanLen bytes and look for %PDF-
	headerLen := int64(pdfHeaderScanLen)
	if size < headerLen {
		headerLen = size
	}
	head := make([]byte, headerLen)
	if _, err := io.ReadFull(rs, head); err != nil {
		return fmt.Errorf("%w: cannot read PDF header: %v", ErrInvalidContent, err)
	}
	idx := bytes.Index(head, pdfMagic)
	if idx < 0 {
		return fmt.Errorf("%w: missing %%PDF- header", ErrFormatMismatch)
	}

	// Version sanity: PDF version must be 1.x or 2.x. ISO 32000-2 (PDF 2.0)
	// is the highest published; anything else is almost certainly garbage
	// that happens to contain the string %PDF- somewhere.
	versionStart := idx + len(pdfMagic)
	if versionStart+3 > len(head) {
		return fmt.Errorf("%w: truncated PDF version", ErrInvalidContent)
	}
	v := head[versionStart : versionStart+3]
	if (v[0] != '1' && v[0] != '2') || v[1] != '.' || v[2] < '0' || v[2] > '9' {
		return fmt.Errorf("%w: unrecognised PDF version %q", ErrInvalidContent, string(v))
	}

	// Trailer check — last pdfTrailerLen bytes must contain %%EOF
	tailLen := int64(pdfTrailerLen)
	if size < tailLen {
		tailLen = size
	}
	if _, err := rs.Seek(size-tailLen, io.SeekStart); err != nil {
		return fmt.Errorf("%w: seek to trailer: %v", ErrInvalidContent, err)
	}
	tail := make([]byte, tailLen)
	if _, err := io.ReadFull(rs, tail); err != nil {
		return fmt.Errorf("%w: cannot read PDF trailer: %v", ErrInvalidContent, err)
	}
	if !bytes.Contains(tail, pdfEOF) {
		return fmt.Errorf("%w: missing %%%%EOF trailer (file may be truncated)", ErrInvalidContent)
	}
	return nil
}
