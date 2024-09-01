package config

import (
	"os"
)

type Config struct {
	DatabaseURL               string
	AuthServicePort           string
	RecommendationServicePort string
	CatalogServicePort        string
	GatewayPort               string
}

func LoadAllConfig() *Config {
	return &Config{
		DatabaseURL:               GetDatabaseUrl(),
		AuthServicePort:           GetAuthServicePort(),
		RecommendationServicePort: GetRecommendationServicePort(),
		CatalogServicePort:        GetCatalogServicePort(),
		GatewayPort:               GetGatewayPort(),
	}
}

func GetDatabaseUrl() string {
	return getEnv("DATABASE_URL", "postgres://user:password@localhost:5432/book_library")
}

func GetAuthServicePort() string {
	return getEnv("AUTH_SERVICE_PORT", ":8081")
}

func GetRecommendationServicePort() string {
	return getEnv("RECOMMENDATION_SERVICE_PORT", ":8082")
}

func GetCatalogServicePort() string {
	return getEnv("CATALOG_SERVICE_PORT", ":8083")
}

func GetJWTSecret() string {
	return getEnv("JWT_SECRET", "your_secret_key")
}

func GetGatewayPort() string {
	return getEnv("GATEWAY_PORT", ":8080")
}

func getEnv(key, fallback string) string {
	if value, exists := os.LookupEnv(key); exists {
		return value
	}
	return fallback
}
