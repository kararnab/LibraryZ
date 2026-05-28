// Package httpx holds small HTTP helpers shared across handler packages.
package httpx

import (
	"log"
	"net/http"
)

// ServerError logs the underlying error (with method + path + a short op label
// for context) and writes a generic 500 to the client. Handlers must use this
// instead of http.Error(w, err.Error(), 500) so DB driver text, schema names,
// file paths, and other internals never reach the wire.
func ServerError(w http.ResponseWriter, r *http.Request, op string, err error) {
	log.Printf("%s %s: %s: %v", r.Method, r.URL.Path, op, err)
	http.Error(w, "internal server error", http.StatusInternalServerError)
}
