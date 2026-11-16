package auth

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/kararnab/iam/v2/httpauth"
)

// An auth check the client abandoned is a 499, not a 5xx; a store that's
// down, or slower than our own deadline, stays 503.
func TestWriteErrorSeparatesAbandonedRequests(t *testing.T) {
	gone, cancel := context.WithCancel(context.Background())
	cancel()
	cases := []struct {
		name string
		ctx  context.Context
		err  error
		want int
	}{
		{"client gone", gone, fmt.Errorf("%w: %w", httpauth.ErrUnavailable, context.Canceled), StatusClientClosedRequest},
		{"store down", context.Background(), httpauth.ErrUnavailable, http.StatusServiceUnavailable},
		{"own deadline", gone, fmt.Errorf("%w: %w", httpauth.ErrUnavailable, context.DeadlineExceeded), http.StatusServiceUnavailable},
		{"canceled, request alive", context.Background(), fmt.Errorf("%w: %w", httpauth.ErrUnavailable, context.Canceled), http.StatusServiceUnavailable},
	}
	for _, c := range cases {
		rec := httptest.NewRecorder()
		r := httptest.NewRequest(http.MethodGet, "/me", nil).WithContext(c.ctx)
		writeError(rec, r, http.StatusServiceUnavailable, c.err)
		if rec.Code != c.want {
			t.Errorf("%s: status %d, want %d", c.name, rec.Code, c.want)
		}
	}
}
