package sanitize

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ErrTooComplex is returned when a file can't be sanitized within the
// isolation limits: the sanitizer ran out of its memory budget or time.
// A PDF whose compressed object streams inflate to gigabytes lands here.
// Maps to HTTP 413 at the handler.
var ErrTooComplex = errors.New("file is too large or complex to process")

// IsolationConfig bounds out-of-process PDF sanitization.
type IsolationConfig struct {
	// MemoryLimit is the extra address space, in bytes, the child may map on
	// top of what it starts with. Enforced hard on Linux (RLIMIT_AS) and as a
	// soft GC target elsewhere. 0 means no limit.
	MemoryLimit int64
	// Timeout bounds one sanitization, including time spent waiting for a
	// slot. 0 means no timeout.
	Timeout time.Duration
	// MaxConcurrent caps how many children run at once, so worst-case memory
	// is MaxConcurrent × MemoryLimit. Values < 1 are treated as 1.
	MaxConcurrent int
	// Executable is the binary re-run in child mode. Empty = os.Executable().
	// That binary must call RunChildIfRequested first thing in main (or
	// TestMain).
	Executable string
}

// Child-mode protocol. The parent runs Executable with only these variables
// in its environment (no DB URL, JWT secret or S3 keys reach a process
// parsing hostile input), the upload as stdin and the output file as stdout.
const (
	childModeEnv  = "LIBRARYZ_SANITIZE_CHILD"
	childMemEnv   = "LIBRARYZ_SANITIZE_CHILD_MEMORY"
	childModePDF  = "pdf"
	exitInvalid   = 3 // ErrInvalidContent; stderr holds the message
	exitOtherFail = 4
)

type isolation struct {
	cfg   IsolationConfig
	exe   string
	slots chan struct{}
}

var (
	isolationMu sync.RWMutex
	isolated    *isolation
)

// EnableIsolation makes PDF sanitization run in a separate process with the
// given limits, so a hostile PDF can exhaust only the child's budget, not the
// server. Call once at startup. Without it, PDFs are sanitized in-process.
func EnableIsolation(cfg IsolationConfig) error {
	exe := cfg.Executable
	if exe == "" {
		var err error
		if exe, err = os.Executable(); err != nil {
			return fmt.Errorf("sanitize: locate executable for isolation: %w", err)
		}
	}
	n := cfg.MaxConcurrent
	if n < 1 {
		n = 1
	}
	isolationMu.Lock()
	isolated = &isolation{cfg: cfg, exe: exe, slots: make(chan struct{}, n)}
	isolationMu.Unlock()
	return nil
}

// DisableIsolation reverts to in-process sanitization. For tests.
func DisableIsolation() {
	isolationMu.Lock()
	isolated = nil
	isolationMu.Unlock()
}

func currentIsolation() *isolation {
	isolationMu.RLock()
	defer isolationMu.RUnlock()
	return isolated
}

// RunChildIfRequested turns this process into a sanitizer child when the
// parent started it in child mode, and never returns in that case. Otherwise
// it returns immediately. Call it first thing in main (and in TestMain of any
// test binary that enables isolation).
func RunChildIfRequested() {
	if os.Getenv(childModeEnv) != childModePDF {
		return
	}
	if v := os.Getenv(childMemEnv); v != "" {
		if n, err := strconv.ParseInt(v, 10, 64); err == nil && n > 0 {
			if err := limitMemory(n); err != nil {
				fmt.Fprintf(os.Stderr, "sanitize child: memory limit: %v\n", err)
				os.Exit(exitOtherFail)
			}
		}
	}
	err := sanitizePDFTo(os.Stdin, os.Stdout)
	switch {
	case err == nil:
		os.Exit(0)
	case errors.Is(err, ErrInvalidContent):
		fmt.Fprint(os.Stderr, err.Error())
		os.Exit(exitInvalid)
	default:
		fmt.Fprint(os.Stderr, err.Error())
		os.Exit(exitOtherFail)
	}
}

// sanitizePDF runs sanitizePDFTo in a child process. The returned
// reader streams the cleaned PDF from a temp file that Close removes.
func (iso *isolation) sanitizePDF(ctx context.Context, rs io.ReadSeeker) (io.ReadCloser, error) {
	if iso.cfg.Timeout > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, iso.cfg.Timeout)
		defer cancel()
	}

	select {
	case iso.slots <- struct{}{}:
		defer func() { <-iso.slots }()
	case <-ctx.Done():
		if errors.Is(ctx.Err(), context.DeadlineExceeded) {
			return nil, fmt.Errorf("%w: timed out waiting for a sanitizer slot", ErrTooComplex)
		}
		return nil, ctx.Err()
	}

	// The child needs a seekable stdin. Uploads over the multipart memory
	// threshold already are temp files; smaller ones are copied to one.
	in, cleanupIn, err := seekableFile(rs)
	if err != nil {
		return nil, err
	}
	defer cleanupIn()

	out, err := os.CreateTemp("", "libraryz-sanitized-*.pdf")
	if err != nil {
		return nil, err
	}
	keep := false
	defer func() {
		if !keep {
			out.Close()
			os.Remove(out.Name())
		}
	}()

	var stderr cappedBuffer
	cmd := exec.CommandContext(ctx, iso.exe)
	cmd.Env = []string{childModeEnv + "=" + childModePDF}
	if iso.cfg.MemoryLimit > 0 {
		cmd.Env = append(cmd.Env, childMemEnv+"="+strconv.FormatInt(iso.cfg.MemoryLimit, 10))
	}
	cmd.Stdin = in
	cmd.Stdout = out
	cmd.Stderr = &stderr
	runErr := cmd.Run()

	if runErr != nil {
		if errors.Is(ctx.Err(), context.DeadlineExceeded) {
			return nil, fmt.Errorf("%w: sanitizer timed out", ErrTooComplex)
		}
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		msg := strings.TrimSpace(stderr.String())
		var exitErr *exec.ExitError
		if errors.As(runErr, &exitErr) && exitErr.ExitCode() == exitInvalid {
			// msg already starts with ErrInvalidContent's text; re-wrap so
			// errors.Is works on this side of the process boundary.
			return nil, fmt.Errorf("%w: %s", ErrInvalidContent,
				strings.TrimPrefix(strings.TrimPrefix(msg, ErrInvalidContent.Error()), ": "))
		}
		// Go runtime OOM under RLIMIT_AS ("fatal error: out of memory"), a
		// failed thread/stack mmap under the same cap, or the container's OOM
		// killer if the budget is set above what the container can give.
		if strings.Contains(msg, "out of memory") ||
			strings.Contains(msg, "cannot allocate memory") ||
			strings.Contains(runErr.Error(), "signal: killed") {
			return nil, fmt.Errorf("%w: sanitizer exceeded its memory budget", ErrTooComplex)
		}
		return nil, fmt.Errorf("sanitize child: %v: %s", runErr, msg)
	}

	if _, err := out.Seek(0, io.SeekStart); err != nil {
		return nil, err
	}
	keep = true
	return &removeOnClose{File: out}, nil
}

// seekableFile returns rs as an *os.File positioned at 0, copying it to a
// temp file if it isn't one already.
func seekableFile(rs io.ReadSeeker) (*os.File, func(), error) {
	if _, err := rs.Seek(0, io.SeekStart); err != nil {
		return nil, nil, fmt.Errorf("seek: %w", err)
	}
	if f, ok := rs.(*os.File); ok {
		return f, func() {}, nil
	}
	tmp, err := os.CreateTemp("", "libraryz-upload-*.pdf")
	if err != nil {
		return nil, nil, err
	}
	cleanup := func() {
		tmp.Close()
		os.Remove(tmp.Name())
	}
	if _, err := io.Copy(tmp, rs); err != nil {
		cleanup()
		return nil, nil, err
	}
	if _, err := tmp.Seek(0, io.SeekStart); err != nil {
		cleanup()
		return nil, nil, err
	}
	return tmp, cleanup, nil
}

// removeOnClose deletes its temp file when closed.
type removeOnClose struct{ *os.File }

func (r *removeOnClose) Close() error {
	err := r.File.Close()
	if rmErr := os.Remove(r.File.Name()); err == nil {
		err = rmErr
	}
	return err
}

// cappedBuffer keeps the first 4 KiB written to it, enough for an error
// message or a Go runtime fatal line, without letting a chatty child grow
// the parent's memory.
type cappedBuffer struct{ bytes.Buffer }

const stderrCap = 4 << 10

func (b *cappedBuffer) Write(p []byte) (int, error) {
	if room := stderrCap - b.Len(); room > 0 {
		if len(p) > room {
			b.Buffer.Write(p[:room])
		} else {
			b.Buffer.Write(p)
		}
	}
	return len(p), nil
}
