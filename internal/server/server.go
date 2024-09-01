package server

import (
	"net/http"

	"github.com/gorilla/mux"
	"github.com/kararnab/libraryZ/internal/auth"
	"github.com/kararnab/libraryZ/internal/catalog"
	"github.com/kararnab/libraryZ/internal/middleware"
	"github.com/kararnab/libraryZ/internal/storage"
	"gorm.io/gorm"
)

type Deps struct {
	DB             *gorm.DB
	Storage        storage.Storage
	MaxUploadBytes int64
}

// New builds the HTTP handler with all routes wired. Used by cmd/libraryz and
// integration tests so production and tests exercise the same router.
func New(d Deps) http.Handler {
	authSvc := auth.NewService(d.DB)
	authH := auth.NewHandler(authSvc)

	catSvc := catalog.NewService(d.DB, d.Storage)
	catH := catalog.NewHandler(catSvc, d.MaxUploadBytes)

	r := mux.NewRouter()
	r.HandleFunc("/health", authH.HealthCheck).Methods(http.MethodGet)
	r.HandleFunc("/auth/signup", authH.SignUp).Methods(http.MethodPost)
	r.HandleFunc("/auth/login", authH.Login).Methods(http.MethodPost)

	r.HandleFunc("/works", catH.ListWorks).Methods(http.MethodGet)
	r.HandleFunc("/works/{id}", catH.GetWork).Methods(http.MethodGet)
	r.HandleFunc("/editions/{id}", catH.GetEdition).Methods(http.MethodGet)
	r.HandleFunc("/editions/{id}/download", catH.DownloadEdition).Methods(http.MethodGet)

	authed := r.NewRoute().Subrouter()
	authed.Use(middleware.Auth)
	authed.HandleFunc("/works", catH.CreateWork).Methods(http.MethodPost)
	authed.HandleFunc("/works/{id}/editions", catH.UploadEdition).Methods(http.MethodPost)

	return r
}

// Migrate runs all schema migrations needed by the routes New() exposes.
func Migrate(db *gorm.DB) error {
	if err := auth.Migrate(db); err != nil {
		return err
	}
	return catalog.Migrate(db)
}
