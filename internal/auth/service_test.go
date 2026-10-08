package auth_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/glebarez/sqlite"
	"github.com/kararnab/libraryZ/internal/auth"
	"github.com/kararnab/libraryZ/internal/migrations"
	"github.com/kararnab/libraryZ/pkg/utils"
	"gorm.io/gorm"
)

func newService(t *testing.T) (*auth.Service, *gorm.DB) {
	t.Helper()
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	if err != nil {
		t.Fatal(err)
	}
	sqlDB, _ := db.DB()
	sqlDB.SetMaxOpenConns(1)
	if err := migrations.Up(context.Background(), db); err != nil {
		t.Fatal(err)
	}
	return auth.NewService(db, time.Minute, time.Hour), db
}

func signup(t *testing.T, svc *auth.Service, email, password string) {
	t.Helper()
	if err := svc.CreateUser(auth.User{Email: email, Password: password, Name: "N"}); err != nil {
		t.Fatalf("signup %s: %v", email, err)
	}
}

func TestCreateUserValidation(t *testing.T) {
	svc, db := newService(t)

	signup(t, svc, "  Ada@Example.COM ", "hunter22")
	var stored auth.User
	if err := db.First(&stored).Error; err != nil {
		t.Fatal(err)
	}
	if stored.Email != "ada@example.com" {
		t.Fatalf("email not normalized: %q", stored.Email)
	}
	if stored.Password == "hunter22" {
		t.Fatal("password stored in plaintext")
	}

	cases := []struct {
		email, password string
		want            error
	}{
		{"ADA@example.com", "hunter22", auth.ErrEmailExists}, // duplicate after normalization
		{"not-an-email", "hunter22", auth.ErrInvalidEmail},
		{"a@b", "hunter22", auth.ErrInvalidEmail},
		{"a b@c.com", "hunter22", auth.ErrInvalidEmail},
		{"a@@c.com", "hunter22", auth.ErrInvalidEmail},
		{"", "hunter22", auth.ErrInvalidEmail},
		{"new@example.com", "short", auth.ErrWeakPassword},
	}
	for _, c := range cases {
		if err := svc.CreateUser(auth.User{Email: c.email, Password: c.password}); !errors.Is(err, c.want) {
			t.Errorf("CreateUser(%q, %q) = %v, want %v", c.email, c.password, err, c.want)
		}
	}
}

func TestAuthenticate(t *testing.T) {
	svc, _ := newService(t)
	signup(t, svc, "ada@example.com", "hunter22")

	pair, err := svc.Authenticate(" ADA@example.com ", "hunter22")
	if err != nil {
		t.Fatalf("login with normalized email variant: %v", err)
	}
	if pair.AccessToken == "" || pair.RefreshToken == "" || pair.TokenType != "Bearer" || pair.ExpiresIn != 60 {
		t.Fatalf("pair = %+v", pair)
	}
	claims, err := utils.VerifyJWT(pair.AccessToken)
	if err != nil || claims.TokenVersion != 0 {
		t.Fatalf("access token: claims=%+v err=%v", claims, err)
	}

	// Wrong password and unknown email are indistinguishable to the caller.
	if _, err := svc.Authenticate("ada@example.com", "wrong-password"); !errors.Is(err, auth.ErrInvalidCredentials) {
		t.Fatalf("wrong password: %v", err)
	}
	if _, err := svc.Authenticate("nobody@example.com", "hunter22"); !errors.Is(err, auth.ErrInvalidCredentials) {
		t.Fatalf("unknown email: %v", err)
	}
}

// The unknown-email path must pay the bcrypt cost too, or response timing
// reveals which emails are registered.
func TestAuthenticateUnknownEmailIsTimingEqualized(t *testing.T) {
	svc, _ := newService(t)
	signup(t, svc, "ada@example.com", "hunter22")

	timeIt := func(email string) time.Duration {
		start := time.Now()
		_, _ = svc.Authenticate(email, "wrong-password")
		return time.Since(start)
	}
	known, unknown := timeIt("ada@example.com"), timeIt("nobody@example.com")
	// bcrypt at DefaultCost is tens of ms; a skipped compare is microseconds.
	if unknown < known/4 {
		t.Fatalf("unknown email returned in %s vs %s for a known one — bcrypt skipped?", unknown, known)
	}
}

func TestRefreshRotatesAndDetectsReuse(t *testing.T) {
	svc, _ := newService(t)
	signup(t, svc, "ada@example.com", "hunter22")
	first, _ := svc.Authenticate("ada@example.com", "hunter22")

	second, err := svc.Refresh(first.RefreshToken)
	if err != nil {
		t.Fatalf("refresh: %v", err)
	}
	if second.RefreshToken == first.RefreshToken || second.AccessToken == "" {
		t.Fatalf("refresh should rotate: %+v", second)
	}

	// Replaying the spent token is reuse: rejected, and the whole family —
	// including the legitimately rotated token — is revoked.
	if _, err := svc.Refresh(first.RefreshToken); !errors.Is(err, auth.ErrInvalidRefreshToken) {
		t.Fatalf("reuse: %v", err)
	}
	if _, err := svc.Refresh(second.RefreshToken); !errors.Is(err, auth.ErrInvalidRefreshToken) {
		t.Fatalf("family should be revoked after reuse: %v", err)
	}

	// Another session (separate login) is unaffected.
	other, _ := svc.Authenticate("ada@example.com", "hunter22")
	if _, err := svc.Refresh(other.RefreshToken); err != nil {
		t.Fatalf("separate session: %v", err)
	}

	if _, err := svc.Refresh("garbage"); !errors.Is(err, auth.ErrInvalidRefreshToken) {
		t.Fatalf("unknown token: %v", err)
	}
}

func TestRefreshRejectsExpired(t *testing.T) {
	svc, db := newService(t)
	signup(t, svc, "ada@example.com", "hunter22")
	pair, _ := svc.Authenticate("ada@example.com", "hunter22")
	db.Model(&auth.RefreshToken{}).Where("1 = 1").Update("expires_at", time.Now().Add(-time.Minute))
	if _, err := svc.Refresh(pair.RefreshToken); !errors.Is(err, auth.ErrInvalidRefreshToken) {
		t.Fatalf("expired: %v", err)
	}
}

func TestLogoutRevokesOnlyThatSession(t *testing.T) {
	svc, _ := newService(t)
	signup(t, svc, "ada@example.com", "hunter22")
	a, _ := svc.Authenticate("ada@example.com", "hunter22")
	b, _ := svc.Authenticate("ada@example.com", "hunter22")

	if err := svc.Logout(a.RefreshToken); err != nil {
		t.Fatal(err)
	}
	if _, err := svc.Refresh(a.RefreshToken); !errors.Is(err, auth.ErrInvalidRefreshToken) {
		t.Fatalf("logged-out session: %v", err)
	}
	if _, err := svc.Refresh(b.RefreshToken); err != nil {
		t.Fatalf("other session: %v", err)
	}
	if err := svc.Logout("unknown"); err != nil {
		t.Fatalf("unknown token logout should be a no-op: %v", err)
	}
}

func TestLogoutAllBumpsTokenVersionAndRevokesRefreshTokens(t *testing.T) {
	svc, db := newService(t)
	signup(t, svc, "ada@example.com", "hunter22")
	a, _ := svc.Authenticate("ada@example.com", "hunter22")
	var u auth.User
	db.First(&u)

	if err := svc.LogoutAll(u.ID); err != nil {
		t.Fatal(err)
	}
	db.First(&u)
	if u.TokenVersion != 1 {
		t.Fatalf("token_version = %d, want 1", u.TokenVersion)
	}
	if _, err := svc.Refresh(a.RefreshToken); !errors.Is(err, auth.ErrInvalidRefreshToken) {
		t.Fatalf("refresh after logout-all: %v", err)
	}
	// New logins carry the new version.
	pair, _ := svc.Authenticate("ada@example.com", "hunter22")
	claims, _ := utils.VerifyJWT(pair.AccessToken)
	if claims.TokenVersion != 1 {
		t.Fatalf("new token tv = %d, want 1", claims.TokenVersion)
	}
}
