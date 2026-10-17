package obs

import (
	"context"
	"log/slog"
	"net/http"
	"runtime/debug"
	"strconv"
	"time"

	"github.com/google/uuid"
	"github.com/gorilla/mux"
)

// RequestIDHeader carries the request id in and out. Kong's correlation-id
// plugin sets it at the edge; a request without a usable one gets a new id.
const RequestIDHeader = "X-Request-ID"

type ctxKey struct{}

// reqInfo is shared between Middleware (outside the router) and RouteTag
// (inside it), because the router hands its handlers a new *http.Request
// that the outer middleware never sees.
type reqInfo struct {
	id    string
	route string
}

// RequestID is the id of the request ctx belongs to, or "".
func RequestID(ctx context.Context) string {
	if info, ok := ctx.Value(ctxKey{}).(*reqInfo); ok {
		return info.id
	}
	return ""
}

// unmatched labels requests no route matched (404s, 405s, preflights), so
// arbitrary paths never become label values.
const unmatched = "unmatched"

// Middleware wraps the whole handler: it assigns the request id, recovers
// panics (500), counts and times the request, and writes one access-log
// line. The line has the route template, not the path (ids) or query
// (search text), and no client IP or user.
func Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		info := &reqInfo{id: incomingID(r.Header.Get(RequestIDHeader)), route: unmatched}
		if info.id == "" {
			info.id = uuid.NewString()
		}
		ctx := context.WithValue(r.Context(), ctxKey{}, info)
		r = r.WithContext(ctx)
		w.Header().Set(RequestIDHeader, info.id)
		rec := &recorder{ResponseWriter: w}

		httpInFlight.Inc()
		defer func() {
			httpInFlight.Dec()
			if p := recover(); p != nil {
				if p == http.ErrAbortHandler {
					panic(p)
				}
				httpPanics.Inc()
				slog.ErrorContext(ctx, "handler panic", "route", info.route, "panic", p, "stack", string(debug.Stack()))
				if !rec.wroteHeader {
					http.Error(rec, "internal server error", http.StatusInternalServerError)
				}
			}
			status := rec.status
			if !rec.wroteHeader {
				status = http.StatusOK
			}
			elapsed := time.Since(start)
			httpRequests.WithLabelValues(r.Method, info.route, strconv.Itoa(status)).Inc()
			httpDuration.WithLabelValues(r.Method, info.route).Observe(elapsed.Seconds())
			level := slog.LevelInfo
			if info.route == "/health" || info.route == "/ready" {
				level = slog.LevelDebug
			}
			slog.LogAttrs(ctx, level, "http request",
				slog.String("method", r.Method),
				slog.String("route", info.route),
				slog.Int("status", status),
				slog.Int64("bytes", rec.bytes),
				slog.Float64("duration_ms", float64(elapsed.Microseconds())/1000),
			)
		}()
		next.ServeHTTP(rec, r)
	})
}

// RouteTag is router middleware (mux.Router.Use) that tells Middleware
// which route template matched.
func RouteTag(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if info, ok := r.Context().Value(ctxKey{}).(*reqInfo); ok {
			if route := mux.CurrentRoute(r); route != nil {
				if tpl, err := route.GetPathTemplate(); err == nil {
					info.route = tpl
				}
			}
		}
		next.ServeHTTP(w, r)
	})
}

// incomingID keeps a caller's request id only if it's short and plain, so
// a header can't inject anything into the logs.
func incomingID(v string) string {
	if v == "" || len(v) > 64 {
		return ""
	}
	for _, c := range v {
		ok := c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_' || c == '.'
		if !ok {
			return ""
		}
	}
	return v
}

// recorder notes the status and size of a response. Unwrap keeps
// http.ResponseController working for the streaming handlers (deadlines,
// flushing).
type recorder struct {
	http.ResponseWriter
	status      int
	wroteHeader bool
	bytes       int64
}

func (r *recorder) WriteHeader(code int) {
	if !r.wroteHeader {
		r.status, r.wroteHeader = code, true
	}
	r.ResponseWriter.WriteHeader(code)
}

func (r *recorder) Write(b []byte) (int, error) {
	if !r.wroteHeader {
		r.status, r.wroteHeader = http.StatusOK, true
	}
	n, err := r.ResponseWriter.Write(b)
	r.bytes += int64(n)
	return n, err
}

func (r *recorder) Unwrap() http.ResponseWriter { return r.ResponseWriter }
