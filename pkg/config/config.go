package config

import (
	"errors"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	DatabaseURL    string
	ListenAddr     string
	StorageDir     string
	JWTSecret      string
	MaxUploadBytes int64
	// AllowedOrigins is the CORS allowlist (exact-match) for browser clients.
	// Empty means "dev mode": only localhost / 127.0.0.1 origins are allowed.
	// Set LIBRARYZ_ALLOWED_ORIGINS (comma-separated) in production.
	AllowedOrigins []string
	// CORSAllowPrivateLAN, when true, additionally allows RFC-1918 private-IP
	// origins (192.168/16, 10/8, 172.16/12) in dev mode — convenient for
	// hitting the web client over the LAN without listing every IP. Ignored
	// when AllowedOrigins is set (that path stays strict). Opt-in:
	// LIBRARYZ_CORS_ALLOW_PRIVATE_LAN=true.
	CORSAllowPrivateLAN bool
	S3Endpoint          string
	S3AccessKey         string
	S3SecretKey         string
	S3Bucket            string
	S3UseSSL            bool
	// Recommendation training knobs.
	RecRetrainInterval time.Duration
	RecFactors         int
	RecAlpha           float64
	// HTTP server timeouts + graceful-shutdown budget. ReadHeaderTimeout is
	// the primary Slowloris defense; ReadTimeout/WriteTimeout are overridden
	// per-request on the upload/download streaming handlers (which can
	// legitimately run long) via http.ResponseController.
	ReadHeaderTimeout time.Duration
	ReadTimeout       time.Duration
	WriteTimeout      time.Duration
	IdleTimeout       time.Duration
	ShutdownTimeout   time.Duration
	// DB connection pool. MaxOpenConns must be sized so
	// instances × MaxOpenConns < Postgres max_connections (default 100) —
	// see PLAN.md Phase 6.1. MaxIdleConns is clamped to MaxOpenConns in db.
	DBMaxOpenConns    int
	DBMaxIdleConns    int
	DBConnMaxLifetime time.Duration
}

func Load() *Config {
	return &Config{
		DatabaseURL:         GetDatabaseUrl(),
		ListenAddr:          GetListenAddr(),
		StorageDir:          GetStorageDir(),
		JWTSecret:           GetJWTSecret(),
		MaxUploadBytes:      GetMaxUploadBytes(),
		AllowedOrigins:      GetAllowedOrigins(),
		CORSAllowPrivateLAN: os.Getenv("LIBRARYZ_CORS_ALLOW_PRIVATE_LAN") == "true",
		S3Endpoint:          os.Getenv("LIBRARYZ_S3_ENDPOINT"),
		S3AccessKey:         os.Getenv("LIBRARYZ_S3_ACCESS_KEY"),
		S3SecretKey:         os.Getenv("LIBRARYZ_S3_SECRET_KEY"),
		S3Bucket:            getEnv("LIBRARYZ_S3_BUCKET", "libraryz"),
		S3UseSSL:            os.Getenv("LIBRARYZ_S3_USE_SSL") == "true",
		RecRetrainInterval:  GetRecRetrainInterval(),
		RecFactors:          GetRecFactors(),
		RecAlpha:            GetRecAlpha(),
		ReadHeaderTimeout:   getDurationEnv("LIBRARYZ_READ_HEADER_TIMEOUT", 10*time.Second),
		ReadTimeout:         getDurationEnv("LIBRARYZ_READ_TIMEOUT", 30*time.Second),
		WriteTimeout:        getDurationEnv("LIBRARYZ_WRITE_TIMEOUT", 60*time.Second),
		IdleTimeout:         getDurationEnv("LIBRARYZ_IDLE_TIMEOUT", 120*time.Second),
		ShutdownTimeout:     getDurationEnv("LIBRARYZ_SHUTDOWN_TIMEOUT", 20*time.Second),
		DBMaxOpenConns:      getIntEnv("LIBRARYZ_DB_MAX_OPEN_CONNS", 25),
		DBMaxIdleConns:      getIntEnv("LIBRARYZ_DB_MAX_IDLE_CONNS", 10),
		DBConnMaxLifetime:   getDurationEnv("LIBRARYZ_DB_CONN_MAX_LIFETIME", time.Hour),
	}
}

// getDurationEnv parses a Go duration string (e.g. "10s", "2m") from env,
// falling back to def on unset/invalid/non-positive.
func getDurationEnv(key string, def time.Duration) time.Duration {
	if v := os.Getenv(key); v != "" {
		if d, err := time.ParseDuration(v); err == nil && d > 0 {
			return d
		}
	}
	return def
}

// getIntEnv parses a positive int from env, falling back to def otherwise.
func getIntEnv(key string, def int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			return n
		}
	}
	return def
}

// GetRecRetrainInterval is how often the in-process trainer re-fits the model.
func GetRecRetrainInterval() time.Duration {
	const def = 6 * time.Hour
	if v := os.Getenv("LIBRARYZ_REC_RETRAIN_INTERVAL"); v != "" {
		if d, err := time.ParseDuration(v); err == nil && d > 0 {
			return d
		}
	}
	return def
}

// GetRecFactors is the latent dimension k (0 → trainer default).
func GetRecFactors() int {
	if v := os.Getenv("LIBRARYZ_REC_FACTORS"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			return n
		}
	}
	return 0
}

// GetRecAlpha is the implicit-feedback confidence scale (0 → trainer default).
func GetRecAlpha() float64 {
	if v := os.Getenv("LIBRARYZ_REC_ALPHA"); v != "" {
		if f, err := strconv.ParseFloat(v, 64); err == nil && f > 0 {
			return f
		}
	}
	return 0
}

func GetDatabaseUrl() string {
	return getEnv("DATABASE_URL", "postgres://user:password@localhost:5432/libraryz?sslmode=disable")
}

func GetListenAddr() string {
	return getEnv("LIBRARYZ_LISTEN_ADDR", ":8080")
}

// GetAllowedOrigins parses LIBRARYZ_ALLOWED_ORIGINS (comma-separated) into an
// exact-match CORS allowlist. Empty/unset means dev mode (localhost only) —
// see server.cors.
func GetAllowedOrigins() []string {
	raw := os.Getenv("LIBRARYZ_ALLOWED_ORIGINS")
	if raw == "" {
		return nil
	}
	var out []string
	for _, o := range strings.Split(raw, ",") {
		if o = strings.TrimSpace(o); o != "" {
			out = append(out, o)
		}
	}
	return out
}

func GetStorageDir() string {
	return getEnv("LIBRARYZ_STORAGE_DIR", "./data/blobs")
}

// InsecureDefaultJWTSecret is the dev/test fallback used when JWT_SECRET is
// unset. It is intentionally well-known; Validate rejects it so it can never
// reach a real deployment. Tests and `go run` against a local DB still work.
const InsecureDefaultJWTSecret = "your_secret_key"

func GetJWTSecret() string {
	return getEnv("JWT_SECRET", InsecureDefaultJWTSecret)
}

// Validate fails fast on configuration that is safe for tests but dangerous in
// a real deployment. Call it from main() before serving; the library path
// (server.New, used by tests) deliberately does not, so the dev fallback
// secret keeps working there.
func (c *Config) Validate() error {
	switch {
	case c.JWTSecret == "":
		return errors.New("JWT_SECRET must be set")
	case c.JWTSecret == InsecureDefaultJWTSecret:
		return errors.New("JWT_SECRET is set to the insecure built-in default; set a real secret")
	case len(c.JWTSecret) < 32:
		return errors.New("JWT_SECRET must be at least 32 bytes")
	}
	return nil
}

func GetMaxUploadBytes() int64 {
	const def int64 = 500 << 20 // 500 MiB
	v := os.Getenv("LIBRARYZ_MAX_UPLOAD_BYTES")
	if v == "" {
		return def
	}
	n, err := strconv.ParseInt(v, 10, 64)
	if err != nil || n <= 0 {
		return def
	}
	return n
}

func getEnv(key, fallback string) string {
	if value, exists := os.LookupEnv(key); exists {
		return value
	}
	return fallback
}
