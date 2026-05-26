// Package sanitize is the L1 ingest filter for uploaded editions: cheap
// structural checks that the bytes match the declared format and aren't a
// zip bomb or polyglot. It does not strip active content (PDF JavaScript,
// EPUB scripts) — that's L2, a separate package when we add it.
//
// Validate is called from the upload handler before the file is content-
// addressed and streamed to storage. It takes a ReadSeeker (multipart.File
// satisfies this) so EPUB validation can read the zip central directory at
// the file's tail without buffering the whole upload in memory.
package sanitize

import (
	"errors"
	"fmt"
	"io"
	"strings"
)

var (
	ErrUnsupportedFormat = errors.New("unsupported format")
	ErrFormatMismatch    = errors.New("file content does not match declared format")
	ErrInvalidContent    = errors.New("file content is invalid for declared format")
	ErrZipBomb           = errors.New("archive exceeds safety limits")
	// ErrActiveContent fires when an upload contains scripting or other
	// active payloads we won't carry: scripts in an EPUB, embedded
	// executables, etc. PDFs don't return this — they're silently stripped
	// in Sanitize. Maps to HTTP 400 at the handler.
	ErrActiveContent = errors.New("file contains active content (scripts, executables, etc.)")
)

// Validate checks that rs's bytes are a well-formed instance of format
// (case-insensitive — "pdf", "PDF", "Pdf" all accepted). On success rs is
// left seeked to 0 so the caller can stream it straight into storage. On
// failure the seek position is undefined.
//
// size is the declared content length, used only as a hint for the EPUB
// reader; if you don't have it, pass -1 and the validator will Seek to
// discover it.
func Validate(format string, rs io.ReadSeeker, size int64) error {
	if size < 0 {
		end, err := rs.Seek(0, io.SeekEnd)
		if err != nil {
			return fmt.Errorf("seek to determine size: %w", err)
		}
		size = end
	}
	if _, err := rs.Seek(0, io.SeekStart); err != nil {
		return fmt.Errorf("seek to start: %w", err)
	}

	switch strings.ToUpper(strings.TrimSpace(format)) {
	case "PDF":
		if err := validatePDF(rs, size); err != nil {
			return err
		}
	case "EPUB":
		if err := validateEPUB(rs, size); err != nil {
			return err
		}
	case "TXT":
		if err := validateTXT(rs, size); err != nil {
			return err
		}
	default:
		return fmt.Errorf("%w: %q", ErrUnsupportedFormat, format)
	}

	if _, err := rs.Seek(0, io.SeekStart); err != nil {
		return fmt.Errorf("seek to start after validation: %w", err)
	}
	return nil
}

// Sanitize is L2: produce a safe-to-store version of an already-Validated
// upload. For PDFs we re-serialize with active content (JavaScript actions,
// embedded files, XFA forms, etc.) stripped; the returned reader holds
// bytes that differ from the input — the new sha256 is what gets stored.
// For EPUBs we strict-reject any script-bearing entry and pass through
// otherwise. For TXTs there's nothing to do — passthrough.
//
// rs must be seeked to 0 (Validate leaves it there on success). On success
// the returned reader is positioned at 0 and the caller streams it into
// storage exactly the way it would have streamed the original.
func Sanitize(format string, rs io.ReadSeeker, size int64) (io.Reader, error) {
	switch strings.ToUpper(strings.TrimSpace(format)) {
	case "PDF":
		return sanitizePDF(rs)
	case "EPUB":
		if err := scanEPUBForScripts(rs, size); err != nil {
			return nil, err
		}
		if _, err := rs.Seek(0, io.SeekStart); err != nil {
			return nil, fmt.Errorf("seek after EPUB scan: %w", err)
		}
		return rs, nil
	case "TXT":
		// Already UTF-8 validated. Pass through.
		return rs, nil
	default:
		return nil, fmt.Errorf("%w: %q", ErrUnsupportedFormat, format)
	}
}
