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

// testDB has a minimal users table: id 1 is a moderator, id 2 isn't, id 3
// has had its tokens revoked (token_version bumped to 1).
func testDB(t *testing.T) *gorm.DB {
	t.Helper()
	db, err := gorm.Open(sqlite.Open("file::memory:"), &gorm.Config{})
	if err != nil {
		t.Fatal(err)
	}
	sqlDB, _ := db.DB()
	sqlDB.SetMaxOpenConns(1)
	for _, s := range []string{
		`CREATE TABLE users (id INTEGER PRIMARY KEY, is_moderator BOOLEAN, token_version INTEGER NOT NULL DEFAULT 0)`,
		`INSERT INTO users (id, is_moderator, token_version) VALUES (1, true, 0), (2, false, 0), (3, false, 1)`,
	} {
		if err := db.Exec(s).Error; err != nil {
			t.Fatal(err)
		}
	}
	return db
}

func sign(t *testing.T, method jwt.SigningMethod, claims jwt.MapClaims, kid string, key any) string {
	t.Helper()
	tok := jwt.NewWithClaims(method, claims)
	if kid != "" {
		tok.Header["kid"] = kid
	}
	s, err := tok.SignedString(key)
	if err != nil {
		t.Fatalf("sign: %v", err)
	}
	return s
}

// currentKID reads the kid the server stamps on tokens it issues.
func currentKID(t *testing.T) string {
	t.Helper()
	tok, _ := utils.GenerateJWT(1, 0, time.Minute)
	parsed, _, err := jwt.NewParser().ParseUnverified(tok, jwt.MapClaims{})
	if err != nil {
		t.Fatal(err)
	}
	return parsed.Header["kid"].(string)
}

func bearer(t *testing.T, userID uint, tv int) string {
	t.Helper()
	tok, err := utils.GenerateJWT(userID, tv, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	return "Bearer " + tok
}

// okHandler records the user id Auth injected.
func okHandler(got *uint) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if uid, ok := UserID(r.Context()); ok {
			*got = uid
		}
		w.WriteHeader(http.StatusOK)
	})
}

func do(h http.Handler, header string) int {
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
	var got uint
	if code := do(Auth(testDB(t))(okHandler(&got)), "bearer "+bearer(t, 2, 0)[len("Bearer "):]); code != http.StatusOK {
		t.Fatalf("code = %d, want 200", code)
	}
	if got != 2 {
		t.Fatalf("user id = %d, want 2", got)
	}
}

func TestAuthRejects(t *testing.T) {
	secret := []byte(config.GetJWTSecret())
	kid := currentKID(t)
	exp := time.Now().Add(time.Hour).Unix()
	claims := func(extra jwt.MapClaims) jwt.MapClaims {
		c := jwt.MapClaims{"user_id": 2, "tv": 0, "exp": exp}
		for k, v := range extra {
			if v == nil {
				delete(c, k)
			} else {
				c[k] = v
			}
		}
		return c
	}
	cases := map[string]string{
		"missing header":        "",
		"wrong scheme":          "Basic " + bearer(t, 2, 0)[len("Bearer "):],
		"embedded bearer":       "xyz" + bearer(t, 2, 0),
		"garbage token":         "Bearer not.a.jwt",
		"expired":               "Bearer " + sign(t, jwt.SigningMethodHS256, claims(jwt.MapClaims{"exp": time.Now().Add(-time.Minute).Unix()}), kid, secret),
		"no exp claim":          "Bearer " + sign(t, jwt.SigningMethodHS256, claims(jwt.MapClaims{"exp": nil}), kid, secret),
		"wrong alg HS512":       "Bearer " + sign(t, jwt.SigningMethodHS512, claims(nil), kid, secret),
		"alg none":              "Bearer " + sign(t, jwt.SigningMethodNone, claims(nil), kid, jwt.UnsafeAllowNoneSignatureType),
		"wrong secret":          "Bearer " + sign(t, jwt.SigningMethodHS256, claims(nil), kid, []byte("some-other-secret")),
		"missing kid":           "Bearer " + sign(t, jwt.SigningMethodHS256, claims(nil), "", secret),
		"unknown kid":           "Bearer " + sign(t, jwt.SigningMethodHS256, claims(nil), "deadbeef", secret),
		"missing user_id":       "Bearer " + sign(t, jwt.SigningMethodHS256, claims(jwt.MapClaims{"user_id": nil}), kid, secret),
		"string user_id":        "Bearer " + sign(t, jwt.SigningMethodHS256, claims(jwt.MapClaims{"user_id": "2"}), kid, secret),
		"revoked token_version": bearer(t, 3, 0),
		"unknown user":          bearer(t, 99, 0),
	}
	db := testDB(t)
	for name, header := range cases {
		t.Run(name, func(t *testing.T) {
			var got uint
			if code := do(Auth(db)(okHandler(&got)), header); code != http.StatusUnauthorized {
				t.Fatalf("code = %d, want 401", code)
			}
		})
	}
	// The current token version for user 3 is accepted.
	var got uint
	if code := do(Auth(db)(okHandler(&got)), bearer(t, 3, 1)); code != http.StatusOK {
		t.Fatalf("current token_version: code = %d, want 200", code)
	}
}

func TestModerator(t *testing.T) {
	db := testDB(t)
	var got uint
	chain := Auth(db)(Moderator(db)(okHandler(&got)))

	if code := do(chain, bearer(t, 1, 0)); code != http.StatusOK {
		t.Errorf("moderator: code = %d, want 200", code)
	}
	if code := do(chain, bearer(t, 2, 0)); code != http.StatusForbidden {
		t.Errorf("non-moderator: code = %d, want 403", code)
	}
	// Moderator without Auth in front has no user id in context.
	if code := do(Moderator(db)(okHandler(&got)), ""); code != http.StatusUnauthorized {
		t.Errorf("unauthenticated: code = %d, want 401", code)
	}
}
