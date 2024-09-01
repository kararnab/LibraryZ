package main

import (
	"github.com/gorilla/mux"
	"github.com/kararnab/libraryZ/internal/auth"
	"github.com/kararnab/libraryZ/pkg/config"
	"github.com/kararnab/libraryZ/pkg/db"
	"gorm.io/gorm"
	"log"
	"net/http"
)

func main() {
	//cfg := config.LoadConfig()
	dbConn, err := db.InitDB(config.GetDatabaseUrl())
	dbOps(dbConn, err)

	authService := auth.NewService(dbConn)
	handler := auth.NewHandler(authService)

	router := mux.NewRouter()
	router.HandleFunc("/health", handler.HealthCheck)
	router.HandleFunc("POST /auth/signup", handler.SignUp)
	router.HandleFunc("POST /auth/login", handler.Login)

	authServicePort := config.GetAuthServicePort()
	log.Printf("Starting Authentication Service on %s...", authServicePort)
	log.Fatal(http.ListenAndServe(authServicePort, nil))
}

func dbOps(dbConn *gorm.DB, err error) {
	if err != nil {
		log.Fatalf("failed to initialize database: %v", err) // Handle error once here
	}
	// Ensure the database connection is closed when the application shuts down
	sqlDB, err := dbConn.DB()
	if err != nil {
		log.Fatalf("failed to get SQL DB from GORM DB: %v", err) // Handle error here
	}
	defer func() {
		if err := sqlDB.Close(); err != nil {
			log.Fatalf("failed to close database: %v", err)
		}
	}()
}
