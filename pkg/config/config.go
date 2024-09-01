package config

import (
	"os"
	"strconv"
)

type Config struct {
	DatabaseURL    string
	ListenAddr     string
	StorageDir     string
	JWTSecret      string
	MaxUploadBytes int64
}

func Load() *Config {
	return &Config{
		DatabaseURL:    GetDatabaseUrl(),
		ListenAddr:     GetListenAddr(),
		StorageDir:     GetStorageDir(),
		JWTSecret:      GetJWTSecret(),
		MaxUploadBytes: GetMaxUploadBytes(),
	}
}

func GetDatabaseUrl() string {
	return getEnv("DATABASE_URL", "postgres://user:password@localhost:5432/libraryz?sslmode=disable")
}

func GetListenAddr() string {
	return getEnv("LIBRARYZ_LISTEN_ADDR", ":8080")
}

func GetStorageDir() string {
	return getEnv("LIBRARYZ_STORAGE_DIR", "./data/blobs")
}

func GetJWTSecret() string {
	return getEnv("JWT_SECRET", "your_secret_key")
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
