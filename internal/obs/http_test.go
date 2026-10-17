package obs

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/mux"
	"github.com/prometheus/client_golang/prometheus/testutil"
)

func TestMiddlewareRecoversPanics(t *testing.T) {
	r := mux.NewRouter()
	r.Use(RouteTag)
	r.HandleFunc("/boom/{id}", func(http.ResponseWriter, *http.Request) { panic("kaboom") })
	before := testutil.ToFloat64(httpPanics)

	rec := httptest.NewRecorder()
	Middleware(r).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/boom/1", nil))

	if rec.Code != http.StatusInternalServerError {
		t.Errorf("status %d, want 500", rec.Code)
	}
	if got := testutil.ToFloat64(httpPanics) - before; got != 1 {
		t.Errorf("panics counted %v, want 1", got)
	}
	if got := testutil.ToFloat64(httpRequests.WithLabelValues("GET", "/boom/{id}", "500")); got != 1 {
		t.Errorf("500 on /boom/{id} counted %v, want 1", got)
	}
}

// The streaming handlers lift their deadlines through
// http.ResponseController, which needs the recorder to unwrap.
func TestRecorderKeepsResponseController(t *testing.T) {
	ts := httptest.NewServer(Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rc := http.NewResponseController(w)
		if err := rc.SetWriteDeadline(time.Time{}); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		if err := rc.Flush(); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
		}
	})))
	defer ts.Close()
	resp, err := http.Get(ts.URL)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Errorf("status %d: ResponseController can't reach the connection through the recorder", resp.StatusCode)
	}
}

func TestIncomingID(t *testing.T) {
	for in, want := range map[string]string{
		"abc-123_X.y":             "abc-123_X.y",
		"":                        "",
		"has space":               "",
		"quote\"":                 "",
		strings.Repeat("a", 65):   "",
		strings.Repeat("a", 64):   strings.Repeat("a", 64),
		"6f1c2b8e-2f43-4c7a-9a55": "6f1c2b8e-2f43-4c7a-9a55",
	} {
		if got := incomingID(in); got != want {
			t.Errorf("incomingID(%q) = %q, want %q", in, got, want)
		}
	}
}
