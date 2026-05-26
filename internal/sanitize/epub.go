package sanitize

import (
	"archive/zip"
	"fmt"
	"io"
	"path"
	"strings"
)

// EPUB is a zip with a strict prologue (OCF §3.3 "OCF ZIP Container"):
//   - The first entry MUST be named "mimetype"
//   - It MUST be stored uncompressed (zip method 0)
//   - Its contents MUST be exactly "application/epub+zip" (20 bytes, no BOM)
//
// We also defend against zip bombs at this layer. Numbers picked to be
// loose enough that any real EPUB (large illustrated children's books run
// ~200 MB uncompressed with a few thousand resource entries) passes, but
// tight enough that the classic 42.zip-style payloads don't.
const (
	epubMaxEntries      = 10_000
	epubMaxUncompressed = 1 << 30 // 1 GiB total across all entries
	epubMaxRatio        = 1000    // per-entry: uncompressed / compressed
	epubMimetypeBody    = "application/epub+zip"
)

func validateEPUB(rs io.ReadSeeker, size int64) error {
	ra, ok := rs.(io.ReaderAt)
	if !ok {
		// All real upload sources (multipart.File, *os.File, *bytes.Reader)
		// implement ReaderAt. If something doesn't, that's a programmer
		// error — fail loud rather than silently buffering.
		return fmt.Errorf("%w: EPUB validator requires io.ReaderAt", ErrInvalidContent)
	}
	zr, err := zip.NewReader(ra, size)
	if err != nil {
		return fmt.Errorf("%w: not a valid zip: %v", ErrFormatMismatch, err)
	}

	if len(zr.File) == 0 {
		return fmt.Errorf("%w: empty archive", ErrInvalidContent)
	}
	if len(zr.File) > epubMaxEntries {
		return fmt.Errorf("%w: %d entries exceeds limit of %d", ErrZipBomb, len(zr.File), epubMaxEntries)
	}

	if err := validateEPUBMimetype(zr.File[0]); err != nil {
		return err
	}

	var totalUncompressed uint64
	for _, f := range zr.File {
		if err := validateZipEntryName(f.Name); err != nil {
			return err
		}
		// Per-entry compression-ratio check: catches "store one byte,
		// inflate to a gigabyte" attacks before we ever try to extract.
		if f.CompressedSize64 > 0 {
			ratio := f.UncompressedSize64 / f.CompressedSize64
			if ratio > epubMaxRatio {
				return fmt.Errorf("%w: entry %q has compression ratio %d:1 (limit %d:1)",
					ErrZipBomb, f.Name, ratio, epubMaxRatio)
			}
		}
		totalUncompressed += f.UncompressedSize64
		if totalUncompressed > epubMaxUncompressed {
			return fmt.Errorf("%w: total uncompressed size exceeds %d bytes",
				ErrZipBomb, uint64(epubMaxUncompressed))
		}
	}
	return nil
}

func validateEPUBMimetype(f *zip.File) error {
	if f.Name != "mimetype" {
		return fmt.Errorf("%w: first entry is %q, expected \"mimetype\"", ErrFormatMismatch, f.Name)
	}
	if f.Method != zip.Store {
		return fmt.Errorf("%w: mimetype entry must be stored uncompressed", ErrFormatMismatch)
	}
	rc, err := f.Open()
	if err != nil {
		return fmt.Errorf("%w: cannot read mimetype entry: %v", ErrInvalidContent, err)
	}
	defer rc.Close()
	// Cap the read at 64 bytes — the legitimate value is 20 bytes; anything
	// longer is suspicious and we don't want to read unbounded.
	body, err := io.ReadAll(io.LimitReader(rc, 64))
	if err != nil {
		return fmt.Errorf("%w: read mimetype: %v", ErrInvalidContent, err)
	}
	if string(body) != epubMimetypeBody {
		return fmt.Errorf("%w: mimetype is %q, expected %q",
			ErrFormatMismatch, string(body), epubMimetypeBody)
	}
	return nil
}

// validateZipEntryName rejects names that could escape the archive root
// (zip-slip) or are absolute paths. Even though we never extract these
// to disk on the server, future L2 sanitization or downstream tools may,
// and a poisoned name in the manifest is a smell on its own.
func validateZipEntryName(name string) error {
	if name == "" {
		return fmt.Errorf("%w: empty entry name", ErrInvalidContent)
	}
	if strings.HasPrefix(name, "/") || strings.Contains(name, `\`) {
		return fmt.Errorf("%w: entry %q has absolute or backslash path", ErrInvalidContent, name)
	}
	clean := path.Clean(name)
	if strings.HasPrefix(clean, "../") || clean == ".." {
		return fmt.Errorf("%w: entry %q escapes archive root", ErrInvalidContent, name)
	}
	for _, part := range strings.Split(name, "/") {
		if part == ".." {
			return fmt.Errorf("%w: entry %q contains .. component", ErrInvalidContent, name)
		}
	}
	return nil
}
