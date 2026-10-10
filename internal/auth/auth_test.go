package auth_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/kararnab/iam/v2"
	"github.com/kararnab/iam/v2/password"
	"github.com/kararnab/iam/v2/session"
	"github.com/kararnab/libraryZ/internal/auth"
)

const secret = "test-secret-test-secret-test-secret!"

// clock is a settable time source.
type clock struct{ t time.Time }

func (c *clock) now() time.Time { return c.t }

func newAuth(t *testing.T, c *clock) *auth.Auth {
	t.Helper()
	a, err := auth.New(auth.Config{DB: freshDB(t), JWTSecret: secret, Now: c.now})
	if err != nil {
		t.Fatal(err)
	}
	return a
}

func signUp(t *testing.T, a *auth.Auth, email, pw string) (*iam.LoginResult, error) {
	t.Helper()
	return a.Service.SignUp(context.Background(), iam.SignUpRequest{
		Provider: password.ProviderName,
		Params:   map[string]string{"username": email, "password": pw},
		Mode:     session.ModeBearer,
	})
}

func TestPasswordMinimumIsEight(t *testing.T) {
	a := newAuth(t, &clock{t: time.Now()})
	if _, err := signUp(t, a, "seven@x.com", "1234567"); !errors.Is(err, password.ErrTooShort) {
		t.Fatalf("7 characters: want ErrTooShort, got %v", err)
	}
	if _, err := signUp(t, a, "eight@x.com", "12345678"); err != nil {
		t.Fatalf("8 characters: %v", err)
	}
}

// Every refresh restarts the session's 30-day lifetime, as before iam: an
// active client is never logged out, an idle one is after 30 days, and
// SessionMaxAge (365 days) caps it regardless.
func TestSessionsSlideWithUse(t *testing.T) {
	ctx := context.Background()
	c := &clock{t: time.Now()}
	a := newAuth(t, c)
	res, err := signUp(t, a, "slide@x.com", "hunter2hunter2")
	if err != nil {
		t.Fatal(err)
	}
	refresh := res.RefreshToken
	start := c.t

	// Refresh every 25 days for most of a year: never expires.
	for c.t = start.Add(25 * 24 * time.Hour); c.t.Before(start.Add(350 * 24 * time.Hour)); c.t = c.t.Add(25 * 24 * time.Hour) {
		pair, err := a.Service.Refresh(ctx, refresh, iam.ClientInfo{})
		if err != nil {
			t.Fatalf("refresh at day %d: %v", int(c.t.Sub(start).Hours()/24), err)
		}
		refresh = pair.RefreshToken
	}
	// Past the max age it ends even though it's in use.
	c.t = start.Add(366 * 24 * time.Hour)
	if _, err := a.Service.Refresh(ctx, refresh, iam.ClientInfo{}); err == nil {
		t.Fatal("refresh past SessionMaxAge succeeded")
	}

	// Unused for 30 days: expired.
	c.t = time.Now()
	res, err = a.Service.Login(ctx, iam.AuthRequest{
		Provider: password.ProviderName,
		Params:   map[string]string{"username": "slide@x.com", "password": "hunter2hunter2"},
		Mode:     session.ModeBearer,
	})
	if err != nil {
		t.Fatal(err)
	}
	c.t = c.t.Add(30*24*time.Hour + time.Minute)
	if _, err := a.Service.Refresh(ctx, res.RefreshToken, iam.ClientInfo{}); err == nil {
		t.Fatal("refresh after 30 idle days succeeded")
	}
}
