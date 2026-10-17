package server_test

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/kararnab/libraryZ/internal/obs"
	"github.com/kararnab/libraryZ/internal/telemetry"
)

const (
	testInstall = "6f1c2b8e-2f43-4c7a-9a55-0d3f3b1f8a10"
	testSession = "0b8f9a7c-1d2e-4f30-8a6b-5c4d3e2f1a09"
)

func postTelemetry(t *testing.T, url, auth string, body any) (int, string) {
	t.Helper()
	var r io.Reader
	switch b := body.(type) {
	case string:
		r = strings.NewReader(b)
	default:
		r = mustJSON(t, b)
	}
	req, _ := http.NewRequest(http.MethodPost, url, r)
	req.Header.Set("Content-Type", "application/json")
	if auth != "" {
		req.Header.Set("Authorization", auth)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	out, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, string(out)
}

var testClient = map[string]any{"platform": "desktop", "app_version": "0.3.0", "os": "Linux"}

func TestTelemetryEventsAcceptsCatalogEventsOnly(t *testing.T) {
	ts, db := newTestServer(t)
	batch := map[string]any{
		"client":     testClient,
		"install_id": testInstall,
		"session_id": testSession,
		"events": []map[string]any{
			{"name": "book_opened", "props": map[string]any{"format": "epub", "source": "device"}},
			{"name": "search", "props": map[string]any{"had_results": false}},
			{"name": "not_in_catalog", "props": map[string]any{}},
			{"name": "search", "props": map[string]any{"had_results": "no"}},                                        // wrong type
			{"name": "book_opened", "props": map[string]any{"format": "epub"}},                                      // missing prop
			{"name": "book_opened", "props": map[string]any{"format": "epub", "source": "device", "title": "Emma"}}, // extra prop
			{"name": "book_opened", "props": map[string]any{"format": "mobi", "source": "device"}},                  // value not listed
		},
	}
	// A bearer token is ignored: telemetry is never tied to an account.
	status, body := postTelemetry(t, ts.URL+"/telemetry/events", "Bearer whatever", batch)
	if status != http.StatusAccepted {
		t.Fatalf("status %d: %s", status, body)
	}
	var res telemetry.Result
	_ = json.Unmarshal([]byte(body), &res)
	if res.Accepted != 2 || res.Rejected != 5 {
		t.Fatalf("result %+v, want 2 accepted, 5 rejected", res)
	}

	var rows []telemetry.EventRecord
	db.Order("name").Find(&rows)
	if len(rows) != 2 {
		t.Fatalf("%d rows stored, want 2", len(rows))
	}
	if rows[0].Name != "book_opened" || string(rows[0].Props) != `{"format":"epub","source":"device"}` {
		t.Errorf("stored %s %s", rows[0].Name, rows[0].Props)
	}
	if rows[0].InstallID != testInstall || rows[0].Platform != "desktop" || rows[0].AppVersion != "0.3.0" {
		t.Errorf("context not stored: %+v", rows[0])
	}
}

func TestTelemetryEventsRefusesBadBatches(t *testing.T) {
	ts, _ := newTestServer(t)
	ok := func() map[string]any {
		return map[string]any{
			"client": testClient, "install_id": testInstall, "session_id": testSession,
			"events": []map[string]any{{"name": "app_opened", "props": map[string]any{}}},
		}
	}
	cases := map[string]func(b map[string]any){
		// Unknown fields are refused so nothing unexpected (a user id, a
		// message) can be stored by accident.
		"unknown field":  func(b map[string]any) { b["user_id"] = 7 },
		"bad install id": func(b map[string]any) { b["install_id"] = "user@example.com" },
		"no session id":  func(b map[string]any) { delete(b, "session_id") },
		"bad platform":   func(b map[string]any) { b["client"] = map[string]any{"platform": "toaster", "app_version": "1"} },
		"bad version":    func(b map[string]any) { b["client"] = map[string]any{"platform": "web", "app_version": "1 <script>"} },
		"no events":      func(b map[string]any) { b["events"] = []any{} },
		"event field": func(b map[string]any) {
			b["events"] = []map[string]any{{"name": "app_opened", "props": map[string]any{}, "query": "x"}}
		},
		"too many events": func(b map[string]any) { b["events"] = make([]map[string]any, 101) },
	}
	for name, mutate := range cases {
		b := ok()
		mutate(b)
		if status, body := postTelemetry(t, ts.URL+"/telemetry/events", "", b); status != http.StatusBadRequest {
			t.Errorf("%s: status %d (%s), want 400", name, status, body)
		}
	}
	big := `{"client":{"platform":"web","app_version":"1"},"events":[` + strings.Repeat(" ", telemetry.MaxEventBody) + `]}`
	if status, _ := postTelemetry(t, ts.URL+"/telemetry/events", "", big); status != http.StatusRequestEntityTooLarge {
		t.Errorf("oversized body: status %d, want 413", status)
	}
}

func TestTelemetryCrashesAreScrubbedAndGrouped(t *testing.T) {
	ts, db := newTestServer(t)
	crash := func(line string) map[string]any {
		return map[string]any{
			"kind":      "fatal",
			"exception": "java.lang.IllegalStateException: can't open “Emma” for reader@example.com",
			"stack": []string{
				"at com.libraryz.data.EpubKt.parse(Epub.kt:" + line + ")",
				"at com.libraryz.ui.Reader.open(/home/arnab/libraryz/Reader.kt:" + line + ")",
				"at app (http://192.168.1.4:8081/composeApp.wasm?token=abc:1:2)",
				"Caused by: java.io.IOException: /home/arnab/Books/Emma.epub",
				"at java.util.zip.ZipFile.open(ZipFile.java:1)",
			},
		}
	}
	for _, line := range []string{"10", "12"} {
		status, body := postTelemetry(t, ts.URL+"/telemetry/crashes", "", map[string]any{
			"client": testClient, "crashes": []any{crash(line)},
		})
		if status != http.StatusAccepted {
			t.Fatalf("status %d: %s", status, body)
		}
	}

	var groups []telemetry.CrashGroup
	db.Find(&groups)
	if len(groups) != 1 {
		t.Fatalf("%d groups, want 1 (line numbers don't split a group)", len(groups))
	}
	g := groups[0]
	if g.Count != 2 || g.Exception != "java.lang.IllegalStateException" {
		t.Errorf("group %+v", g)
	}
	for _, leak := range []string{"Emma", "example.com", "arnab", "192.168", "token="} {
		if strings.Contains(g.Stack, leak) || strings.Contains(g.Exception, leak) {
			t.Errorf("stored crash still contains %q:\n%s", leak, g.Stack)
		}
	}
	if !strings.Contains(g.Stack, "Caused by: java.io.IOException\n") {
		t.Errorf("cause type lost:\n%s", g.Stack)
	}
	if g.TopFrame != "at com.libraryz.data.EpubKt.parse(Epub.kt:12)" {
		t.Errorf("top frame %q", g.TopFrame)
	}
	var reports int64
	db.Model(&telemetry.CrashReport{}).Count(&reports)
	if reports != 2 {
		t.Errorf("%d reports, want 2", reports)
	}

	if status, _ := postTelemetry(t, ts.URL+"/telemetry/crashes", "", map[string]any{
		"client": testClient, "crashes": []any{map[string]any{"kind": "fatal", "exception": "X", "message": "a title"}},
	}); status != http.StatusBadRequest {
		t.Errorf("a crash with a message field: status %d, want 400", status)
	}
}

func TestRequestIDAndRouteMetrics(t *testing.T) {
	ts, _ := newTestServer(t)

	resp, err := http.Get(ts.URL + "/health")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.Header.Get(obs.RequestIDHeader) == "" {
		t.Error("no request id on the response")
	}

	req, _ := http.NewRequest(http.MethodGet, ts.URL+"/health", nil)
	req.Header.Set(obs.RequestIDHeader, "kong-abc-123")
	resp, _ = http.DefaultClient.Do(req)
	resp.Body.Close()
	if got := resp.Header.Get(obs.RequestIDHeader); got != "kong-abc-123" {
		t.Errorf("request id %q, want the caller's", got)
	}
	req.Header.Set(obs.RequestIDHeader, "bad id with=spaces")
	resp, _ = http.DefaultClient.Do(req)
	resp.Body.Close()
	if got := resp.Header.Get(obs.RequestIDHeader); got == "" || strings.ContainsAny(got, " =") {
		t.Errorf("request id %q: an unsafe caller id must be replaced", got)
	}

	// A route on the authenticated subrouter, refused: labelled by its
	// template. An unknown path: "unmatched", never the path itself.
	resp, _ = http.Get(ts.URL + "/me/library/3f2a1c4e-0000-4000-8000-000000000000")
	resp.Body.Close()
	resp, _ = http.Get(ts.URL + "/no/such/path/secret-title")
	resp.Body.Close()

	rec := httptest.NewRecorder()
	obs.MetricsHandler().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/metrics", nil))
	metrics := rec.Body.String()
	for _, want := range []string{
		`libraryz_http_requests_total{method="GET",route="/health",status="200"}`,
		`libraryz_http_requests_total{method="GET",route="/me/library/{id}",status="401"}`,
		`libraryz_http_requests_total{method="GET",route="unmatched",status="404"}`,
		`libraryz_client_events_total`,
	} {
		if !strings.Contains(metrics, want) {
			t.Errorf("metrics lack %s", want)
		}
	}
	if bytes.Contains(rec.Body.Bytes(), []byte("secret-title")) {
		t.Error("a request path leaked into a metric label")
	}
}
