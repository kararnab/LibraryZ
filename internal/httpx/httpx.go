// Package httpx holds small HTTP helpers shared across handler packages.
package httpx

import (
	"log/slog"
	"net/http"
)

// ServerError logs the underlying error (with method + path + a short op label
// for context, and the request id) and writes a generic 500 to the client.
// Handlers must use this instead of http.Error(w, err.Error(), 500) so DB
// driver text, schema names, file paths, and other internals never reach the
// wire.
func ServerError(w http.ResponseWriter, r *http.Request, op string, err error) {
	slog.ErrorContext(r.Context(), "server error", "method", r.Method, "path", r.URL.Path, "op", op, "err", err)
	http.Error(w, "internal server error", http.StatusInternalServerError)
}
