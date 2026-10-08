package utils

import (
	"testing"
	"time"
)

func withKeys(t *testing.T, k keyring) {
	t.Helper()
	old := keys
	keys = k
	t.Cleanup(func() { keys = old })
}

func TestJWTRoundTrip(t *testing.T) {
	tok, err := GenerateJWT(7, 3, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	c, err := VerifyJWT(tok)
	if err != nil {
		t.Fatal(err)
	}
	if c.UserID != 7 || c.TokenVersion != 3 || c.IssuedAt == nil || c.ExpiresAt == nil {
		t.Fatalf("claims = %+v", c)
	}
	if d := c.ExpiresAt.Sub(c.IssuedAt.Time); d != time.Minute {
		t.Fatalf("ttl = %s, want 1m", d)
	}
}

func TestJWTDefaultTTL(t *testing.T) {
	tok, _ := GenerateJWT(1, 0, 0)
	c, err := VerifyJWT(tok)
	if err != nil {
		t.Fatal(err)
	}
	if d := c.ExpiresAt.Sub(c.IssuedAt.Time); d != DefaultAccessTokenTTL {
		t.Fatalf("ttl = %s, want %s", d, DefaultAccessTokenTTL)
	}
}

func TestJWTRotation(t *testing.T) {
	const oldSecret = "old-secret-old-secret-old-secret-0001"
	const newSecret = "new-secret-new-secret-new-secret-0002"

	withKeys(t, newKeyring(oldSecret, ""))
	issuedBeforeRotation, _ := GenerateJWT(1, 0, time.Minute)

	// Rolling deploy: new secret signs, old one still verifies.
	withKeys(t, newKeyring(newSecret, oldSecret))
	if _, err := VerifyJWT(issuedBeforeRotation); err != nil {
		t.Fatalf("token signed with the previous secret should verify during rotation: %v", err)
	}
	issuedAfter, _ := GenerateJWT(1, 0, time.Minute)
	if _, err := VerifyJWT(issuedAfter); err != nil {
		t.Fatalf("token signed with the new secret: %v", err)
	}

	// Rotation complete: previous dropped.
	withKeys(t, newKeyring(newSecret, ""))
	if _, err := VerifyJWT(issuedBeforeRotation); err == nil {
		t.Fatal("token signed with a retired secret must not verify")
	}
	if _, err := VerifyJWT(issuedAfter); err != nil {
		t.Fatalf("new-secret token after rotation: %v", err)
	}
}
