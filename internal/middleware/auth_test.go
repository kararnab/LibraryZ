package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/glebarez/sqlite"
	"github.com/golang-jwt/jwt/v5"
	"github.com/kararnab/libraryZ/pkg/config"
	"github.com/kararnab/libraryZ/pkg/utils"
	"gorm.io/gorm"
)

func signHS(t *testing.T, method jwt.SigningMethod, claims jwt.MapClaims, key any) string {
	t.Helper()
	s, err := jwt.NewWithClaims(method, claims).SignedString(key)
	if err != nil {
		t.Fatalf("sign: %v", err)
	}
	return s
}

func secret() []byte { return []byte(config.GetJWTSecret()) }

// okHandler records the user id Auth injected.
func okHandler(got *uint) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if uid, ok := UserID(r.Context()); ok {
			*got = uid
		}
		w.WriteHeader(http.StatusOK)
	})
}

func doAuth(h http.Handler, header string) int {
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	if header != "" {
		req.Header.Set("Authorization", header)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec.Code
}

func TestBearerToken(t *testing.T) {
	cases := []struct {
		in    string
		token string
		ok    bool
	}{
		{"Bearer abc", "abc", true},
		{"bearer abc", "abc", true},
		{"BEARER abc", "abc", true},
		{"  Bearer   abc  ", "abc", true},
		{"Bearer", "", false},
		{"Bearer ", "", false},
		{"xyzBearer abc", "", false},
		{"Basic foo Bearer abc", "", false},
		{"Basic abc", "", false},
		{"Bearer abc def", "", false},
		{"", "", false},
	}
	for _, c := range cases {
		tok, ok := bearerToken(c.in)
		if ok != c.ok || tok != c.token {
			t.Errorf("bearerToken(%q) = (%q, %v), want (%q, %v)", c.in, tok, ok, c.token, c.ok)
		}
	}
}

func TestAuthAcceptsValidToken(t *testing.T) {
	tok, err := utils.GenerateJWT(42)
	if err != nil {
		t.Fatal(err)
	}
	var got uint
	if code := doAuth(Auth(okHandler(&got)), "bearer "+tok); code != http.StatusOK {
		t.Fatalf("code = %d, want 200", code)
	}
	if got != 42 {
		t.Fatalf("user id = %d, want 42", got)
	}
}

func TestAuthRejects(t *testing.T) {
	valid, _ := utils.GenerateJWT(1)
	exp := time.Now().Add(time.Hour).Unix()
	cases := map[string]string{
		"missing header":  "",
		"wrong scheme":    "Basic " + valid,
		"embedded bearer": "xyzBearer " + valid,
		"garbage token":   "Bearer not.a.jwt",
		"expired":         "Bearer " + signHS(t, jwt.SigningMethodHS256, jwt.MapClaims{"user_id": 1, "exp": time.Now().Add(-time.Minute).Unix()}, secret()),
		"no exp claim":    "Bearer " + signHS(t, jwt.SigningMethodHS256, jwt.MapClaims{"user_id": 1}, secret()),
		"wrong alg HS512": "Bearer " + signHS(t, jwt.SigningMethodHS512, jwt.MapClaims{"user_id": 1, "exp": exp}, secret()),
		"alg none":        "Bearer " + signHS(t, jwt.SigningMethodNone, jwt.MapClaims{"user_id": 1, "exp": exp}, jwt.UnsafeAllowNoneSignatureType),
		"wrong secret":    "Bearer " + signHS(t, jwt.SigningMethodHS256, jwt.MapClaims{"user_id": 1, "exp": exp}, []byte("some-other-secret")),
		"missing user_id": "Bearer " + signHS(t, jwt.SigningMethodHS256, jwt.MapClaims{"exp": exp}, secret()),
		"string user_id":  "Bearer " + signHS(t, jwt.SigningMethodHS256, jwt.MapClaims{"user_id": "1", "exp": exp}, secret()),
	}
	for name, header := range cases {
		t.Run(name, func(t *testing.T) {
			var got uint
			if code := doAuth(Auth(okHandler(&got)), header); code != http.StatusUnauthorized {
				t.Fatalf("code = %d, want 401", code)
			}
		})
	}
}

func TestModerator(t *testing.T) {
	db, err := gorm.Open(sqlite.Open("file::memory:"), &gorm.Config{})
	if err != nil {
		t.Fatal(err)
	}
	if err := db.Exec(`CREATE TABLE users (id INTEGER PRIMARY KEY, is_moderator BOOLEAN)`).Error; err != nil {
		t.Fatal(err)
	}
	db.Exec(`INSERT INTO users (id, is_moderator) VALUES (1, true), (2, false)`)

	var got uint
	chain := Auth(Moderator(db)(okHandler(&got)))
	bearer := func(id uint) string {
		tok, _ := utils.GenerateJWT(id)
		return "Bearer " + tok
	}

	if code := doAuth(chain, bearer(1)); code != http.StatusOK {
		t.Errorf("moderator: code = %d, want 200", code)
	}
	if code := doAuth(chain, bearer(2)); code != http.StatusForbidden {
		t.Errorf("non-moderator: code = %d, want 403", code)
	}
	if code := doAuth(chain, bearer(99)); code != http.StatusForbidden {
		t.Errorf("unknown user: code = %d, want 403", code)
	}
	// Moderator without Auth in front has no user id in context.
	if code := doAuth(Moderator(db)(okHandler(&got)), ""); code != http.StatusUnauthorized {
		t.Errorf("unauthenticated: code = %d, want 401", code)
	}
}
