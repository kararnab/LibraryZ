package server

import (
	"net"
	"net/http"
	"net/url"

	"github.com/gorilla/mux"
	"github.com/kararnab/libraryZ/internal/auth"
	"github.com/kararnab/libraryZ/internal/catalog"
	"github.com/kararnab/libraryZ/internal/contribution"
	"github.com/kararnab/libraryZ/internal/library"
	"github.com/kararnab/libraryZ/internal/middleware"
	"github.com/kararnab/libraryZ/internal/recommendation"
	"github.com/kararnab/libraryZ/internal/storage"
	"gorm.io/gorm"
)

type Deps struct {
	DB             *gorm.DB
	Storage        storage.Storage
	MaxUploadBytes int64
	// AllowedOrigins is the exact-match CORS allowlist for browser clients.
	// Empty = dev mode (localhost / 127.0.0.1 origins only). See cors.
	AllowedOrigins []string
	// AllowPrivateLAN additionally permits RFC-1918 private-IP origins in dev
	// mode (when AllowedOrigins is empty). Ignored otherwise.
	AllowPrivateLAN bool
}

// New builds the HTTP handler with all routes wired. Used by cmd/libraryz and
// integration tests so production and tests exercise the same router.
func New(d Deps) http.Handler {
	authSvc := auth.NewService(d.DB)
	authH := auth.NewHandler(authSvc)

	catSvc := catalog.NewService(d.DB, d.Storage)
	catH := catalog.NewHandler(catSvc, d.MaxUploadBytes)

	contribSvc := contribution.NewService(d.DB)
	contribH := contribution.NewHandler(contribSvc)

	librarySvc := library.NewService(d.DB)
	libraryH := library.NewHandler(librarySvc)

	recSvc := recommendation.NewService(d.DB)
	recH := recommendation.NewHandler(recSvc)

	r := mux.NewRouter()
	r.HandleFunc("/health", authH.HealthCheck).Methods(http.MethodGet)
	r.HandleFunc("/auth/signup", authH.SignUp).Methods(http.MethodPost)
	r.HandleFunc("/auth/login", authH.Login).Methods(http.MethodPost)

	r.HandleFunc("/works", catH.ListWorks).Methods(http.MethodGet)
	// /works/search before /works/{id} so mux matches "search" as a
	// literal segment rather than capturing it into {id} (which would
	// then fail uuid.Parse and 400 with the wrong message).
	r.HandleFunc("/works/search", catH.SearchWorks).Methods(http.MethodGet)
	r.HandleFunc("/works/{id}", catH.GetWork).Methods(http.MethodGet)
	r.HandleFunc("/editions/{id}", catH.GetEdition).Methods(http.MethodGet)
	r.HandleFunc("/editions/{id}/download", catH.DownloadEdition).Methods(http.MethodGet)

	r.HandleFunc("/contributions", contribH.List).Methods(http.MethodGet)
	r.HandleFunc("/contributions/{id}", contribH.Get).Methods(http.MethodGet)

	authed := r.NewRoute().Subrouter()
	authed.Use(middleware.Auth)
	authed.HandleFunc("/auth/me", authH.Me).Methods(http.MethodGet)
	authed.HandleFunc("/works", catH.CreateWork).Methods(http.MethodPost)
	authed.HandleFunc("/works/{id}/editions", catH.UploadEdition).Methods(http.MethodPost)
	authed.HandleFunc("/works/{id}/contributions", contribH.Submit).Methods(http.MethodPost)
	authed.HandleFunc("/me/contributions", contribH.ListMine).Methods(http.MethodGet)

	// Personal library — all per-user, all auth-gated, none moderator-gated.
	// {id} is the work id (consistent with /works/{id}/contributions).
	authed.HandleFunc("/me/library", libraryH.List).Methods(http.MethodGet)
	authed.HandleFunc("/me/library/{id}", libraryH.Get).Methods(http.MethodGet)
	authed.HandleFunc("/me/library/{id}", libraryH.Upsert).Methods(http.MethodPut)
	authed.HandleFunc("/me/library/{id}", libraryH.Delete).Methods(http.MethodDelete)

	// Personal recommendations — per-user; MF model with content/popularity
	// fallback. Dismiss hides a suggestion from future results.
	authed.HandleFunc("/me/recommendations", recH.Recommend).Methods(http.MethodGet)
	authed.HandleFunc("/me/recommendations/{id}/dismiss", recH.Dismiss).Methods(http.MethodPost)

	// Moderator-gated subset of the authed routes. Subrouter inherits the
	// parent Auth middleware via gorilla/mux's chain composition, so a
	// request hitting /contributions/{id}/approve gets Auth -> Moderator.
	mod := authed.NewRoute().Subrouter()
	mod.Use(middleware.Moderator(d.DB))
	mod.HandleFunc("/contributions/{id}/approve", contribH.Approve).Methods(http.MethodPost)
	mod.HandleFunc("/contributions/{id}/reject", contribH.Reject).Methods(http.MethodPost)

	// Wrap the whole router so OPTIONS preflights are handled by us before
	// mux's method matcher returns 405. r.Use(...) would run AFTER routing.
	return cors(r, d.AllowedOrigins, d.AllowPrivateLAN)
}

// cors is the CORS layer for browser clients. It reflects the request Origin
// only when allowOrigin permits it — so CORS headers are never emitted for an
// untrusted origin. Native clients (Android/Desktop/iOS) don't trigger CORS at
// all, so this only governs the Wasm/web client.
//
// allowed is an exact-match allowlist (set LIBRARYZ_ALLOWED_ORIGINS in prod).
// When it's empty we're in dev mode: only localhost / loopback origins (any
// port, any scheme) are permitted — plus RFC-1918 private IPs if allowPrivateLAN
// is set — which covers the local web dev server without opening the API to
// arbitrary sites.
func cors(next http.Handler, allowed []string, allowPrivateLAN bool) http.Handler {
	allow := allowOrigin(allowed, allowPrivateLAN)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		origin := r.Header.Get("Origin")
		if origin != "" && allow(origin) {
			w.Header().Set("Access-Control-Allow-Origin", origin)
			w.Header().Set("Vary", "Origin")
			w.Header().Set("Access-Control-Allow-Methods",
				"GET, POST, PUT, DELETE, OPTIONS")
			w.Header().Set("Access-Control-Allow-Headers",
				"Authorization, Content-Type, X-Requested-With")
			w.Header().Set("Access-Control-Expose-Headers", "Authorization, X-Content-SHA256")
		}
		// Preflights are always answered 204 (a disallowed origin simply gets
		// no Allow-Origin header, so the browser blocks it). Non-OPTIONS
		// requests proceed regardless — CORS gates the browser's *reading* of
		// the response, not the server's processing.
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

// allowOrigin returns a predicate deciding whether an Origin may be reflected.
func allowOrigin(allowed []string, allowPrivateLAN bool) func(string) bool {
	if len(allowed) > 0 {
		set := make(map[string]struct{}, len(allowed))
		for _, o := range allowed {
			set[o] = struct{}{}
		}
		return func(origin string) bool {
			_, ok := set[origin]
			return ok
		}
	}
	// Dev fallback: localhost / loopback on any scheme+port, plus RFC-1918
	// private IPs when explicitly opted in.
	return func(origin string) bool {
		u, err := url.Parse(origin)
		if err != nil {
			return false
		}
		host := u.Hostname()
		if host == "localhost" {
			return true
		}
		ip := net.ParseIP(host)
		if ip == nil {
			return false
		}
		if ip.IsLoopback() {
			return true
		}
		return allowPrivateLAN && ip.IsPrivate()
	}
}

// Migrate runs all schema migrations needed by the routes New() exposes.
func Migrate(db *gorm.DB) error {
	if err := auth.Migrate(db); err != nil {
		return err
	}
	if err := catalog.Migrate(db); err != nil {
		return err
	}
	if err := contribution.Migrate(db); err != nil {
		return err
	}
	if err := library.Migrate(db); err != nil {
		return err
	}
	return recommendation.Migrate(db)
}
