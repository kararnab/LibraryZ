package main

import (
	"github.com/kararnab/libraryZ/internal/catalog"
	"github.com/kararnab/libraryZ/pkg/config"
	"github.com/kararnab/libraryZ/pkg/db"
	"log"
	"net/http"
)

func main() {
	cfg := config.LoadAllConfig()
	dbConn, _ := db.InitDB(config.GetDatabaseUrl())

	catalogService := catalog.NewService(dbConn)
	handler := catalog.NewHandler(catalogService)

	http.HandleFunc("/catalog/books", handler.GetBooks)
	http.HandleFunc("/catalog/book", handler.AddBook)
	http.HandleFunc("/catalog/book/{id}", handler.DeleteBook)

	log.Printf("Starting Catalog Service on %s...", cfg.CatalogServicePort)
	log.Fatal(http.ListenAndServe(cfg.CatalogServicePort, nil))
}
