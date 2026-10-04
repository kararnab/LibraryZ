//go:build !linux

package sanitize

import "runtime/debug"

// limitMemory is best-effort off Linux: a soft GC target only. macOS doesn't
// enforce RLIMIT_AS, so a hostile PDF can still grow the child, but never
// the server process. Production runs on Linux, where the cap is hard.
func limitMemory(extra int64) error {
	debug.SetMemoryLimit(extra)
	return nil
}
