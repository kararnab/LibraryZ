package server_test

import (
	"net/http"
	"net/url"
	"strings"
	"testing"
)

// Every string or number a request carries has a bound: past it the
// answer is 400, never a stored megabyte or a database error.

func TestLibraryUpsertOutOfBounds400(t *testing.T) {
	ts, _ := newTestServer(t)
	auth := signupAndLogin(t, ts.URL, "limits-lib@x.com", testPassword, "L")
	wid := createWork(t, ts.URL, auth, "Dune", "")

	for name, body := range map[string]map[string]any{
		"notes":          {"notes": strings.Repeat("n", 20_001)},
		"shelf":          {"shelf": strings.Repeat("s", 101)},
		"negative page":  {"current_page": -1},
		"huge page":      {"current_page": 1_000_001},
		"negative total": {"total_pages": -5},
		"huge total":     {"total_pages": 1_000_001},
		"notes base":     {"notes": "x", "notes_base": strings.Repeat("b", 1000)},
	} {
		req, _ := http.NewRequest(http.MethodPut, ts.URL+"/me/library/"+wid, mustJSON(t, body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Authorization", auth)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		if resp.StatusCode != http.StatusBadRequest {
			t.Errorf("%s: want 400, got %d %s", name, resp.StatusCode, readBody(resp))
		}
		resp.Body.Close()
	}

	// At the bounds it's fine.
	putLibrary(t, ts.URL, auth, wid, map[string]any{
		"notes": strings.Repeat("ñ", 20_000), "shelf": strings.Repeat("s", 100),
		"current_page": 1_000_000, "total_pages": 1_000_000,
	})
}

func TestSignUpOutOfBounds400(t *testing.T) {
	ts, _ := newTestServer(t)
	for name, body := range map[string]map[string]string{
		"name":  {"email": "limits-name@x.com", "password": testPassword, "name": strings.Repeat("a", 201)},
		"email": {"email": strings.Repeat("e", 250) + "@x.com", "password": testPassword, "name": "E"},
	} {
		resp := postJSON(t, ts.URL+"/auth/signup", body)
		if resp.StatusCode != http.StatusBadRequest {
			t.Errorf("%s: want 400, got %d %s", name, resp.StatusCode, readBody(resp))
		}
		resp.Body.Close()
	}
}

func TestSearchQueryTooLong400(t *testing.T) {
	ts, _ := newTestServer(t)
	resp, err := http.Get(ts.URL + "/works/search?q=" + url.QueryEscape(strings.Repeat("q", 201)))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("want 400, got %d %s", resp.StatusCode, readBody(resp))
	}
}

func TestUploadLanguageTooLong400(t *testing.T) {
	ts, _ := newTestServer(t)
	auth := signupLogin(t, ts.URL, "limits-lang@b.com")
	_, w := postWork(t, ts.URL, auth, map[string]any{"title": "Lang"})
	resp := uploadEdition(t, ts.URL, auth, w["id"].(string), "pdf", strings.Repeat("l", 65), buildTestPDF(t))
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("want 400, got %d %s", resp.StatusCode, readBody(resp))
	}
}
