package main

import (
	"log"
	"net/http"

	"github.com/kararnab/libraryZ/internal/server"
	"github.com/kararnab/libraryZ/internal/storage"
	"github.com/kararnab/libraryZ/pkg/config"
	"github.com/kararnab/libraryZ/pkg/db"
)

func main() {
	cfg := config.Load()

	dbConn, err := db.InitDB(cfg.DatabaseURL)
	if err != nil {
		log.Fatalf("db: %v", err)
	}
	if err := server.Migrate(dbConn); err != nil {
		log.Fatalf("migrate: %v", err)
	}

	store, err := storage.NewLocal(cfg.StorageDir)
	if err != nil {
		log.Fatalf("storage: %v", err)
	}

	h := server.New(server.Deps{
		DB:             dbConn,
		Storage:        store,
		MaxUploadBytes: cfg.MaxUploadBytes,
	})

	log.Printf("libraryz listening on %s (storage=%s)", cfg.ListenAddr, cfg.StorageDir)
	log.Fatal(http.ListenAndServe(cfg.ListenAddr, h))
}
