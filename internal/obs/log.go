// Package obs is the server's operational observability: structured logs,
// request ids, and Prometheus metrics. It is always on and needs no consent
// because it never records who a request was for: no user ids, emails, IPs,
// query strings or book titles. Client crash reports and usage events,
// which do need consent, are internal/telemetry.
package obs

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"strings"
)

// SetupLogging makes slog's default logger (and with it the standard log
// package) write format ("json" or "text") at level ("debug", "info",
// "warn", "error"). Each record logged with a request's context carries its
// request_id.
func SetupLogging(format, level string) error {
	var lvl slog.Level
	if err := lvl.UnmarshalText([]byte(strings.TrimSpace(level))); err != nil {
		return fmt.Errorf("LIBRARYZ_LOG_LEVEL: %q: want debug, info, warn or error", level)
	}
	opts := &slog.HandlerOptions{Level: lvl}
	var h slog.Handler
	switch strings.ToLower(strings.TrimSpace(format)) {
	case "", "text":
		h = slog.NewTextHandler(os.Stderr, opts)
	case "json":
		h = slog.NewJSONHandler(os.Stderr, opts)
	default:
		return fmt.Errorf("LIBRARYZ_LOG_FORMAT: %q: want json or text", format)
	}
	slog.SetDefault(slog.New(contextHandler{h}))
	return nil
}

// contextHandler adds the request id from the record's context.
type contextHandler struct{ slog.Handler }

func (h contextHandler) Handle(ctx context.Context, r slog.Record) error {
	if id := RequestID(ctx); id != "" {
		r.AddAttrs(slog.String("request_id", id))
	}
	return h.Handler.Handle(ctx, r)
}

func (h contextHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	return contextHandler{h.Handler.WithAttrs(attrs)}
}

func (h contextHandler) WithGroup(name string) slog.Handler {
	return contextHandler{h.Handler.WithGroup(name)}
}
