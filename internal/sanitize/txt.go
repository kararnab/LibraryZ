package sanitize

import (
	"bufio"
	"fmt"
	"io"
	"unicode/utf8"
)

// TXT is plain text and we render it with Compose Text. The only real
// failure mode is invalid UTF-8 (would render as replacement characters
// or, worse, mojibake), and the only abuse vector is a multi-gigabyte
// "novel" that exists to stuff cheap storage. 100 MiB is ~50 million
// English words — bigger than the complete works of Shakespeare and
// Tolstoy combined.
const txtMaxSize = 100 << 20

func validateTXT(rs io.ReadSeeker, size int64) error {
	if size > txtMaxSize {
		return fmt.Errorf("%w: TXT is %d bytes, limit is %d", ErrInvalidContent, size, int64(txtMaxSize))
	}
	// Validate UTF-8 in 64 KiB chunks, carrying a remainder across reads
	// so a multi-byte rune split across the chunk boundary isn't flagged
	// as invalid. utf8.Valid is fastpath SIMD on modern Go.
	br := bufio.NewReaderSize(rs, 64*1024)
	var carry [4]byte
	carryLen := 0
	buf := make([]byte, 64*1024)
	for {
		n, err := br.Read(buf)
		if n > 0 {
			chunk := append(carry[:carryLen], buf[:n]...)
			// Find the last rune boundary so we don't split a multi-byte
			// sequence and false-fail on the trailing partial rune.
			boundary := lastRuneBoundary(chunk)
			if !utf8.Valid(chunk[:boundary]) {
				return fmt.Errorf("%w: TXT contains invalid UTF-8", ErrInvalidContent)
			}
			carryLen = copy(carry[:], chunk[boundary:])
		}
		if err == io.EOF {
			break
		}
		if err != nil {
			return fmt.Errorf("%w: read TXT: %v", ErrInvalidContent, err)
		}
	}
	// Validate the tail carry — at EOF it must be a complete rune (or empty).
	if carryLen > 0 && !utf8.Valid(carry[:carryLen]) {
		return fmt.Errorf("%w: TXT ends with invalid UTF-8 sequence", ErrInvalidContent)
	}
	return nil
}

// lastRuneBoundary returns the index where a complete-runes prefix ends.
// It walks back at most 3 bytes (UTF-8 sequences are 1–4 bytes), checking
// whether the trailing bytes look like the start of an incomplete sequence.
func lastRuneBoundary(b []byte) int {
	if len(b) == 0 {
		return 0
	}
	max := 4
	if len(b) < max {
		max = len(b)
	}
	for i := 1; i <= max; i++ {
		c := b[len(b)-i]
		if c < 0x80 {
			// ASCII byte: everything up to and including this is complete.
			return len(b) - i + 1
		}
		if c&0xC0 == 0xC0 {
			// Start of a multi-byte sequence. If the remaining bytes after
			// it form a complete rune, include them; otherwise stop here.
			expected := utf8RuneLen(c)
			if i >= expected {
				return len(b)
			}
			return len(b) - i
		}
		// Continuation byte (10xxxxxx) — keep walking back.
	}
	// No start byte found in last 4 bytes: pathological, treat all as carry.
	return 0
}

func utf8RuneLen(start byte) int {
	switch {
	case start&0xE0 == 0xC0:
		return 2
	case start&0xF0 == 0xE0:
		return 3
	case start&0xF8 == 0xF0:
		return 4
	}
	return 1
}
