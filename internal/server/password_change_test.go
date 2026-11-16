package server_test

import (
	"net/http"
	"strings"
	"testing"
)

// Changing the password checks the current one, keeps this session, ends
// the others, and moves sign-in to the new password.
func TestChangePasswordEndToEnd(t *testing.T) {
	base, _ := newRecoveryServer(t)
	const email = "change-pw@example.com"
	signupLogin(t, base, email)
	here := loginPair(t, base, email, testPassword)
	other := loginPair(t, base, email, testPassword)
	bearer := "Bearer " + here.AccessToken

	change := func(current, next string) (int, string) {
		return post(t, base+"/me/password", bearer, map[string]string{"current_password": current, "new_password": next})
	}
	if code, _ := post(t, base+"/me/password", "", map[string]string{"current_password": testPassword, "new_password": "whatever-long"}); code != http.StatusUnauthorized {
		t.Fatalf("anonymous: %d", code)
	}
	// A wrong current password is 403, not 401: the client must not take
	// it for an expired token.
	if code, body := change("not-my-password", "a-new-passphrase"); code != http.StatusForbidden || !strings.Contains(body, "incorrect") {
		t.Fatalf("wrong current: %d %q", code, body)
	}
	if code, body := change(testPassword, "short"); code != http.StatusBadRequest || !strings.Contains(body, "at least 8") {
		t.Fatalf("short new: %d %q", code, body)
	}
	if code, _ := change("", "a-new-passphrase"); code != http.StatusBadRequest {
		t.Fatalf("empty current: %d", code)
	}
	const next = "a-new-passphrase"
	if code, body := change(testPassword, next); code != http.StatusNoContent {
		t.Fatalf("change: %d %q", code, body)
	}

	if got := loginStatus(t, base, email, testPassword); got != http.StatusUnauthorized {
		t.Fatalf("old password still works: %d", got)
	}
	if got := loginStatus(t, base, email, next); got != http.StatusOK {
		t.Fatalf("new password refused: %d", got)
	}
	if code, _ := post(t, base+"/auth/refresh", "", map[string]string{"refresh_token": here.RefreshToken}); code != http.StatusOK {
		t.Fatalf("this session was signed out: %d", code)
	}
	if code, _ := post(t, base+"/auth/refresh", "", map[string]string{"refresh_token": other.RefreshToken}); code != http.StatusUnauthorized {
		t.Fatalf("the other session survived: %d", code)
	}
}

// Wrong current passwords count on the login throttle, so the form can't
// guess faster than sign-in; a password the policy rejects costs nothing.
func TestChangePasswordIsThrottledLikeLogin(t *testing.T) {
	base, _ := newRecoveryServer(t)
	const email = "change-pw-throttle@example.com"
	bearer := signupLogin(t, base, email)

	for i := 0; i < 10; i++ {
		code, _ := post(t, base+"/me/password", bearer, map[string]string{"current_password": testPassword, "new_password": "short"})
		if code != http.StatusBadRequest {
			t.Fatalf("policy rejection %d: %d", i, code)
		}
	}
	for i := 0; ; i++ {
		code, _ := post(t, base+"/me/password", bearer, map[string]string{"current_password": "wrong-guess", "new_password": "a-new-passphrase"})
		if code == http.StatusTooManyRequests {
			if i == 0 {
				t.Fatal("throttled before any wrong guess: policy rejections were counted")
			}
			break
		}
		if code != http.StatusForbidden || i > 20 {
			t.Fatalf("guess %d: %d", i, code)
		}
	}
	// Sign-in shares the budget.
	if got := loginStatus(t, base, email, testPassword); got != http.StatusTooManyRequests {
		t.Fatalf("login after throttled change: %d", got)
	}
}
