//go:build linux

package sanitize

import (
	"fmt"
	"os"
	"runtime/debug"
	"strconv"
	"strings"
	"syscall"
)

// limitMemory caps this process's address space at its current size plus
// extra bytes (RLIMIT_AS). The Go runtime reserves a sizeable chunk of
// address space up front, so the cap is relative to what's mapped now. When
// pdfcpu tries to grow past it, the runtime dies with "fatal error: out of
// memory", which the parent maps to ErrTooComplex. The soft GC limit makes
// the runtime collect harder before it gets there, so legitimate large PDFs
// don't trip the hard cap on uncollected garbage.
func limitMemory(extra int64) error {
	b, err := os.ReadFile("/proc/self/statm")
	if err != nil {
		return err
	}
	fields := strings.Fields(string(b))
	if len(fields) == 0 {
		return fmt.Errorf("unexpected /proc/self/statm: %q", b)
	}
	pages, err := strconv.ParseUint(fields[0], 10, 64)
	if err != nil {
		return err
	}
	limit := pages*uint64(os.Getpagesize()) + uint64(extra)
	debug.SetMemoryLimit(extra)
	return syscall.Setrlimit(syscall.RLIMIT_AS, &syscall.Rlimit{Cur: limit, Max: limit})
}
