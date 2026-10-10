package server

import (
	"context"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"time"

	"github.com/gorilla/mux"
	"github.com/kararnab/iam/v2/policy"
	"github.com/kararnab/iam/v2/ratelimit"
	"github.com/kararnab/libraryZ/internal/auth"
	"github.com/kararnab/libraryZ/internal/catalog"
	"github.com/kararnab/libraryZ/internal/contribution"
	"github.com/kararnab/libraryZ/internal/library"
	"github.com/kararnab/libraryZ/internal/migrations"
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
	// JWTSecret signs access tokens (at least 32 bytes); JWTPreviousSecret
	// still verifies them during a rotation. See auth.Config.
	JWTSecret         string
	JWTPreviousSecret string
	// Token lifetimes; zero means the auth package defaults.
	AccessTokenTTL  time.Duration
	RefreshTokenTTL time.Duration
	// VerifySessionOnAccess makes revocation immediate (one lookup per
	// authenticated request). See auth.Config.
	VerifySessionOnAccess bool
	// Login throttles; nil means per-process in-memory limiters.
	LoginLimiterPerAccount ratelimit.Limiter
	LoginLimiterPerIP      ratelimit.Limiter
	// TrustedProxies whose X-Forwarded-For names the client IP (Kong).
	TrustedProxies []netip.Prefix
}

// New builds the HTTP handler with all routes wired. Used by cmd/libraryz and
// integration tests so production and tests exercise the same router.
func New(d Deps) (http.Handler, error) {
	a, err := auth.New(auth.Config{
		DB:                    d.DB,
		JWTSecret:             d.JWTSecret,
		JWTPreviousSecret:     d.JWTPreviousSecret,
		AccessTokenTTL:        d.AccessTokenTTL,
		RefreshTokenTTL:       d.RefreshTokenTTL,
		VerifySessionOnAccess: d.VerifySessionOnAccess,
		PerLogin:              d.LoginLimiterPerAccount,
		PerIP:                 d.LoginLimiterPerIP,
		TrustedProxies:        d.TrustedProxies,
	})
	if err != nil {
		return nil, err
	}
	authH := auth.NewHandler(a)
	requireMod := func(action policy.Action, resource string, h http.HandlerFunc) http.Handler {
		return a.HTTP.RequirePermission(action, resource, nil)(h)
	}

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
	r.HandleFunc("/ready", ready(d.DB, d.Storage)).Methods(http.MethodGet)
	r.HandleFunc("/auth/signup", authH.SignUp).Methods(http.MethodPost)
	r.HandleFunc("/auth/login", authH.Login).Methods(http.MethodPost)
	r.HandleFunc("/auth/refresh", authH.Refresh).Methods(http.MethodPost)
	r.HandleFunc("/auth/logout", authH.Logout).Methods(http.MethodPost)

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
	authed.Use(a.HTTP.RequireAuth)
	authed.HandleFunc("/auth/me", authH.Me).Methods(http.MethodGet)
	authed.HandleFunc("/auth/logout-all", authH.LogoutAll).Methods(http.MethodPost)
	authed.HandleFunc("/me/sessions", authH.ListSessions).Methods(http.MethodGet)
	authed.HandleFunc("/me/sessions/{id}", authH.RevokeSession).Methods(http.MethodDelete)
	authed.HandleFunc("/works", catH.CreateWork).Methods(http.MethodPost)
	authed.HandleFunc("/works/{id}/editions", catH.UploadEdition).Methods(http.MethodPost)
	// Moderators, or the creator of a still-empty work; checked in the service.
	authed.HandleFunc("/works/{id}", catH.DeleteWork).Methods(http.MethodDelete)
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

	// Moderator-gated: RBAC permissions (role "moderator", see auth.New),
	// checked against the roles in the caller's access token.
	authed.Handle("/contributions/{id}/approve",
		requireMod(auth.ActionModerate, auth.ResourceContribution, contribH.Approve)).Methods(http.MethodPost)
	authed.Handle("/contributions/{id}/reject",
		requireMod(auth.ActionModerate, auth.ResourceContribution, contribH.Reject)).Methods(http.MethodPost)
	authed.Handle("/editions/{id}",
		requireMod(auth.ActionDelete, auth.ResourceEdition, catH.DeleteEdition)).Methods(http.MethodDelete)

	// Protect identifies the caller from the bearer token (anonymous if
	// absent or invalid) for every route; RequireAuth/RequirePermission then
	// gate. CORS wraps everything so OPTIONS preflights are answered before
	// mux's method matcher returns 405 (r.Use would run after routing).
	return cors(a.HTTP.Protect(r), d.AllowedOrigins, d.AllowPrivateLAN), nil
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
			w.Header().Set("Access-Control-Expose-Headers", "Authorization, Content-Disposition, X-Content-SHA256")
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

// Migrate brings the schema up to date. See internal/migrations.
func Migrate(db *gorm.DB) error {
	return migrations.Up(context.Background(), db)
}
