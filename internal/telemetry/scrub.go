package telemetry

import (
	"crypto/sha256"
	"encoding/hex"
	"regexp"
	"strings"
)

// Bounds on a crash report.
const (
	MaxFrames        = 64
	MaxFrameRunes    = 300
	MaxExceptionLen  = 200
	fingerprintDepth = 8
)

var (
	// An exception is a (qualified) type name and nothing after it: the
	// message is where titles, paths and URLs would be.
	exceptionRe = regexp.MustCompile(`^[A-Za-z_$][A-Za-z0-9_$.]*`)
	// Ordered: URLs before paths, tokens before hex.
	scrubbers = []struct {
		re   *regexp.Regexp
		with string
	}{
		{regexp.MustCompile(`[A-Za-z][A-Za-z0-9+.-]*://[^/\s)]*`), ""},      // scheme://host:port (keeps the file part)
		{regexp.MustCompile(`\?[^\s):]*`), ""},                              // query strings
		{regexp.MustCompile(`[\w.+-]+@[\w-]+(\.[\w-]+)+`), "<email>"},       // emails
		{regexp.MustCompile(`(/home|/Users|/data/user/\d+)/[^/\s)]+`), "~"}, // home directories (user names)
		{regexp.MustCompile(`(?i)[A-Z]:\\Users\\[^\\\s)]+`), "~"},
		{regexp.MustCompile(`eyJ[\w-]+\.[\w-]+\.[\w-]+`), "<token>"}, // JWTs
		{regexp.MustCompile(`(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b`), "<id>"},
		{regexp.MustCompile(`(?i)\b[0-9a-f]{32,}\b`), "<hex>"},
	}
	// Dropped when fingerprinting, so a crash keeps its group across builds.
	lineNumbersRe = regexp.MustCompile(`:\d+|0x[0-9a-fA-F]+|\$\d+|\[\d+\]`)
)

// cleanException keeps only the leading type name of e.
func cleanException(e string) string {
	t := exceptionRe.FindString(strings.TrimSpace(e))
	if len(t) > MaxExceptionLen {
		t = t[:MaxExceptionLen]
	}
	if t == "" {
		return "Unknown"
	}
	return t
}

// cleanStack scrubs each frame, keeps only the type of a "Caused by:"
// line, and caps frame count and length.
func cleanStack(frames []string) []string {
	if len(frames) > MaxFrames {
		frames = frames[:MaxFrames]
	}
	out := make([]string, 0, len(frames))
	for _, f := range frames {
		f = strings.TrimSpace(f)
		if cause, ok := strings.CutPrefix(f, "Caused by:"); ok {
			out = append(out, "Caused by: "+cleanException(cause))
			continue
		}
		for _, s := range scrubbers {
			f = s.re.ReplaceAllString(f, s.with)
		}
		if r := []rune(f); len(r) > MaxFrameRunes {
			f = string(r[:MaxFrameRunes])
		}
		if f != "" {
			out = append(out, f)
		}
	}
	return out
}

// fingerprint groups reports of the same crash: the platform, the
// exception, and the top frames without line numbers or offsets.
func fingerprint(platform, exception string, stack []string) string {
	h := sha256.New()
	h.Write([]byte(platform + "\n" + exception + "\n"))
	n := 0
	for _, f := range stack {
		if n == fingerprintDepth || strings.HasPrefix(f, "Caused by:") {
			break
		}
		h.Write([]byte(lineNumbersRe.ReplaceAllString(f, "") + "\n"))
		n++
	}
	return hex.EncodeToString(h.Sum(nil))
}

// topFrame is the first frame in the app's own code, else the first frame.
func topFrame(stack []string) string {
	for _, f := range stack {
		if strings.Contains(f, "libraryz") {
			return f
		}
	}
	if len(stack) > 0 {
		return stack[0]
	}
	return ""
}
