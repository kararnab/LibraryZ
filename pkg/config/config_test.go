package config

import (
	"strings"
	"testing"
	"time"
)

const goodSecret = "0123456789abcdef0123456789abcdef" // 32 bytes

func TestValidate(t *testing.T) {
	cases := []struct {
		name     string
		secret   string
		previous string
		wantErr  string
	}{
		{"ok", goodSecret, "", ""},
		{"ok with previous", goodSecret, strings.Repeat("p", 32), ""},
		{"empty", "", "", "must be set"},
		{"built-in default", InsecureDefaultJWTSecret, "", "insecure built-in default"},
		{"short", "short-secret", "", "at least 32 bytes"},
		{"31 bytes", goodSecret[:31], "", "at least 32 bytes"},
		{"short previous", goodSecret, "short", "JWT_SECRET_PREVIOUS must be at least 32 bytes"},
		{"previous equals current", goodSecret, goodSecret, "must differ"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			err := (&Config{JWTSecret: c.secret, JWTPreviousSecret: c.previous}).Validate()
			switch {
			case c.wantErr == "" && err != nil:
				t.Fatalf("unexpected error: %v", err)
			case c.wantErr != "" && (err == nil || !strings.Contains(err.Error(), c.wantErr)):
				t.Fatalf("err = %v, want containing %q", err, c.wantErr)
			}
		})
	}
}

func TestDurationAndIntEnvParsing(t *testing.T) {
	const key = "LIBRARYZ_TEST_KNOB"
	for val, want := range map[string]time.Duration{
		"":      7 * time.Second, // unset → default
		"90s":   90 * time.Second,
		"2h":    2 * time.Hour,
		"bogus": 7 * time.Second,
		"0s":    7 * time.Second, // non-positive → default
		"-5s":   7 * time.Second,
		"10":    7 * time.Second, // no unit is invalid
	} {
		t.Setenv(key, val)
		if got := getDurationEnv(key, 7*time.Second); got != want {
			t.Errorf("getDurationEnv(%q) = %s, want %s", val, got, want)
		}
	}
	for val, want := range map[string]int{
		"":    3,
		"25":  25,
		"0":   3,
		"-1":  3,
		"x":   3,
		"2.5": 3,
	} {
		t.Setenv(key, val)
		if got := getIntEnv(key, 3); got != want {
			t.Errorf("getIntEnv(%q) = %d, want %d", val, got, want)
		}
	}
}

func TestLoadReadsEnv(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://app@pgbouncer/db")
	t.Setenv("LIBRARYZ_MIGRATE_DATABASE_URL", "")
	t.Setenv("LIBRARYZ_ALLOWED_ORIGINS", " https://a.example , ,https://b.example ")
	t.Setenv("LIBRARYZ_MAX_UPLOAD_BYTES", "1024")
	t.Setenv("LIBRARYZ_ACCESS_TOKEN_TTL", "5m")
	t.Setenv("LIBRARYZ_AUTO_MIGRATE", "false")

	c := Load()
	if got := strings.Join(c.AllowedOrigins, "|"); got != "https://a.example|https://b.example" {
		t.Errorf("AllowedOrigins = %q", got)
	}
	if c.MaxUploadBytes != 1024 || c.AccessTokenTTL != 5*time.Minute || c.AutoMigrate {
		t.Errorf("Load: max=%d ttl=%s automigrate=%v", c.MaxUploadBytes, c.AccessTokenTTL, c.AutoMigrate)
	}
	// An empty migrate DSN falls back to DATABASE_URL rather than "".
	if c.MigrateDatabaseURL != "postgres://app@pgbouncer/db" {
		t.Errorf("empty LIBRARYZ_MIGRATE_DATABASE_URL: got %q, want DATABASE_URL", c.MigrateDatabaseURL)
	}
	t.Setenv("LIBRARYZ_MIGRATE_DATABASE_URL", "postgres://app@postgres/db")
	if got := Load().MigrateDatabaseURL; got != "postgres://app@postgres/db" {
		t.Errorf("MigrateDatabaseURL = %q", got)
	}
}

func TestMaxUploadBytesFallsBack(t *testing.T) {
	for _, v := range []string{"", "0", "-1", "lots"} {
		t.Setenv("LIBRARYZ_MAX_UPLOAD_BYTES", v)
		if got := GetMaxUploadBytes(); got != 500<<20 {
			t.Errorf("GetMaxUploadBytes(%q) = %d, want 500 MiB", v, got)
		}
	}
}
