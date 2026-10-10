package server_test

import (
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"testing"

	"github.com/kararnab/libraryZ/internal/auth"
	"github.com/kararnab/onemailer"
)

// outbox records queued emails instead of sending them.
type outbox struct {
	mu   sync.Mutex
	msgs []onemailer.Message
}

func (o *outbox) Enqueue(m onemailer.Message) bool {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.msgs = append(o.msgs, m)
	return true
}

// sent returns the messages of kind sent to to, oldest first.
func (o *outbox) sent(kind, to string) []onemailer.Message {
	o.mu.Lock()
	defer o.mu.Unlock()
	var out []onemailer.Message
	for _, m := range o.msgs {
		if m.Kind == kind && m.To == to {
			out = append(out, m)
		}
	}
	return out
}

const recoveryPublicURL = "https://library.example.org"

var linkRE = regexp.MustCompile(`https://library\.example\.org/(reset-password|verify-email)\?token=([^\s"<]+)`)

// tokenIn pulls the token out of the email's link, checking that the text
// part carries the same token as the code and that the HTML links to it.
func tokenIn(t *testing.T, m onemailer.Message, path string) string {
	t.Helper()
	match := linkRE.FindStringSubmatch(m.Text)
	if match == nil || match[1] != path {
		t.Fatalf("no %s link in:\n%s", path, m.Text)
	}
	tok, err := url.QueryUnescape(match[2])
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(m.Text, "\n"+tok+"\n") {
		t.Fatalf("the code isn't in the text part")
	}
	if !strings.Contains(m.HTML, `href="`+recoveryPublicURL+"/"+path+"?token=") {
		t.Fatalf("the HTML part doesn't link to %s", path)
	}
	return tok
}

func newRecoveryServer(t *testing.T) (string, *outbox) {
	t.Helper()
	deps, _ := newTestDeps(t)
	box := &outbox{}
	deps.Mail = auth.Mail{Outbox: box, PublicURL: recoveryPublicURL}
	return newServer(t, deps).URL, box
}

func post(t *testing.T, url, bearer string, body any) (int, string) {
	t.Helper()
	b, _ := json.Marshal(body)
	req, _ := http.NewRequest(http.MethodPost, url, strings.NewReader(string(b)))
	req.Header.Set("Content-Type", "application/json")
	if bearer != "" {
		req.Header.Set("Authorization", bearer)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	out, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, string(out)
}

func emailVerified(t *testing.T, base, bearer string) bool {
	t.Helper()
	req, _ := http.NewRequest(http.MethodGet, base+"/auth/me", nil)
	req.Header.Set("Authorization", bearer)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var me struct {
		EmailVerified bool `json:"email_verified"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&me); err != nil {
		t.Fatal(err)
	}
	return me.EmailVerified
}

func loginStatus(t *testing.T, base, email, password string) int {
	t.Helper()
	code, _ := post(t, base+"/auth/login", "", map[string]string{"email": email, "password": password})
	return code
}

func TestPasswordResetEndToEnd(t *testing.T) {
	base, box := newRecoveryServer(t)
	const email = "reset-e2e@example.com"
	signupLogin(t, base, email)
	old := loginPair(t, base, email, testPassword)

	if code, _ := post(t, base+"/auth/password-reset", "", map[string]string{"email": "  Reset-E2E@Example.com "}); code != http.StatusAccepted {
		t.Fatalf("request: %d", code)
	}
	msgs := box.sent(auth.KindPasswordReset, email)
	if len(msgs) != 1 {
		t.Fatalf("want 1 reset email, got %d", len(msgs))
	}
	if msgs[0].Subject != "Reset your LibraryZ password" {
		t.Fatalf("subject %q", msgs[0].Subject)
	}
	tok := tokenIn(t, msgs[0], "reset-password")

	// A too-short password is refused, and the token survives it.
	if code, body := post(t, base+"/auth/password-reset/complete", "", map[string]string{"token": tok, "new_password": "short"}); code != http.StatusBadRequest || !strings.Contains(body, "at least 8") {
		t.Fatalf("short password: %d %q", code, body)
	}
	const newPassword = "a-much-better-passphrase"
	if code, body := post(t, base+"/auth/password-reset/complete", "", map[string]string{"token": tok, "new_password": newPassword}); code != http.StatusNoContent {
		t.Fatalf("complete: %d %q", code, body)
	}

	if got := loginStatus(t, base, email, testPassword); got != http.StatusUnauthorized {
		t.Fatalf("old password still works: %d", got)
	}
	if got := loginStatus(t, base, email, newPassword); got != http.StatusOK {
		t.Fatalf("new password refused: %d", got)
	}
	// The reset signed every session out: the old refresh token is dead.
	if code, _ := post(t, base+"/auth/refresh", "", map[string]string{"refresh_token": old.RefreshToken}); code != http.StatusUnauthorized {
		t.Fatalf("old session survived the reset: %d", code)
	}
	// A token works once.
	if code, body := post(t, base+"/auth/password-reset/complete", "", map[string]string{"token": tok, "new_password": "yet-another-passphrase"}); code != http.StatusGone {
		t.Fatalf("reuse: %d %q", code, body)
	}
}

func TestPasswordResetDoesNotRevealAccounts(t *testing.T) {
	base, box := newRecoveryServer(t)
	code, body := post(t, base+"/auth/password-reset", "", map[string]string{"email": "nobody-here@example.com"})
	if code != http.StatusAccepted || !strings.Contains(body, "accepted") {
		t.Fatalf("unknown address: %d %q", code, body)
	}
	if n := len(box.sent(auth.KindPasswordReset, "nobody-here@example.com")); n != 0 {
		t.Fatalf("mailed an unknown address %d times", n)
	}
	if code, _ := post(t, base+"/auth/password-reset", "", map[string]string{"email": "not-an-email"}); code != http.StatusBadRequest {
		t.Fatalf("malformed address: %d", code)
	}
	if code, _ := post(t, base+"/auth/password-reset/complete", "", map[string]string{"token": "made-up", "new_password": "whatever-long"}); code != http.StatusGone {
		t.Fatalf("made-up token: %d", code)
	}
}

func TestPasswordResetCooldownKeepsTheFirstLink(t *testing.T) {
	base, box := newRecoveryServer(t)
	const email = "reset-cooldown@example.com"
	signupLogin(t, base, email)

	for i := 0; i < 2; i++ {
		if code, _ := post(t, base+"/auth/password-reset", "", map[string]string{"email": email}); code != http.StatusAccepted {
			t.Fatalf("request %d: %d", i, code)
		}
	}
	msgs := box.sent(auth.KindPasswordReset, email)
	if len(msgs) != 1 {
		t.Fatalf("the cooldown let %d emails through", len(msgs))
	}
	// The capped request issued no new token, so the emailed link still works.
	tok := tokenIn(t, msgs[0], "reset-password")
	if code, body := post(t, base+"/auth/password-reset/complete", "", map[string]string{"token": tok, "new_password": "fresh-passphrase-1"}); code != http.StatusNoContent {
		t.Fatalf("first link broken by the capped request: %d %q", code, body)
	}
}

func TestEmailVerificationEndToEnd(t *testing.T) {
	base, box := newRecoveryServer(t)
	const email = "verify-e2e@example.com"
	bearer := signupLogin(t, base, email)

	// Sign-up sent the first link.
	msgs := box.sent(auth.KindEmailVerification, email)
	if len(msgs) != 1 {
		t.Fatalf("want 1 verification email after signup, got %d", len(msgs))
	}
	if emailVerified(t, base, bearer) {
		t.Fatal("verified before following the link")
	}
	// Asking again within the cooldown is refused (the user's own account,
	// so a plain 429 is fine).
	if code, _ := post(t, base+"/me/email-verification", bearer, nil); code != http.StatusTooManyRequests {
		t.Fatalf("resend in cooldown: %d", code)
	}

	tok := tokenIn(t, msgs[0], "verify-email")
	code, body := post(t, base+"/auth/email-verification/complete", "", map[string]string{"token": tok})
	if code != http.StatusOK || !strings.Contains(body, email) {
		t.Fatalf("complete: %d %q", code, body)
	}
	if !emailVerified(t, base, bearer) {
		t.Fatal("not verified after following the link")
	}
	if code, _ := post(t, base+"/auth/email-verification/complete", "", map[string]string{"token": tok}); code != http.StatusGone {
		t.Fatalf("reuse: %d", code)
	}
	// Verified: nothing more to send.
	if code, _ := post(t, base+"/me/email-verification", bearer, nil); code != http.StatusNoContent {
		t.Fatalf("resend when verified: %d", code)
	}
	if code, _ := post(t, base+"/me/email-verification", "", nil); code != http.StatusUnauthorized {
		t.Fatalf("anonymous resend: %d", code)
	}
}

func TestRecoveryWithMailOff(t *testing.T) {
	deps, _ := newTestDeps(t)
	base := newServer(t, deps).URL // zero auth.Mail: nothing is sent
	const email = "mail-off@example.com"
	signupLogin(t, base, email)
	if code, _ := post(t, base+"/auth/password-reset", "", map[string]string{"email": email}); code != http.StatusAccepted {
		t.Fatalf("request with mail off: %d", code)
	}
}
