// Package auth is LibraryZ's authentication and authorization layer, built
// on github.com/kararnab/iam: argon2id passwords, bearer sessions (short
// access JWTs + rotating refresh tokens with reuse detection), per-account
// and per-IP login throttling, and RBAC.
//
// LibraryZ supplies the storage (GORM adapters in users.go and sessions.go,
// checked by iam's storetest suite) and the HTTP endpoints (handler.go);
// iam supplies the rest.
package auth

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"net/http"
	"net/netip"
	"time"

	"github.com/kararnab/iam/v2"
	"github.com/kararnab/iam/v2/httpauth"
	"github.com/kararnab/iam/v2/invite"
	"github.com/kararnab/iam/v2/password"
	"github.com/kararnab/iam/v2/policy"
	"github.com/kararnab/iam/v2/provider"
	"github.com/kararnab/iam/v2/ratelimit"
	"github.com/kararnab/iam/v2/session"
	"github.com/kararnab/iam/v2/token/jwt"
	"github.com/kararnab/iam/v2/token/keys"
	"gorm.io/gorm"
)

// RoleModerator may approve/reject contributions and take works and
// editions down. Granted out-of-band; see UserRole.
const RoleModerator = "moderator"

// Permissions checked by the router (httpauth.RequirePermission).
const (
	ActionModerate policy.Action = "moderate"
	ActionDelete   policy.Action = "delete"

	ResourceContribution = "contribution"
	ResourceEdition      = "edition"
	ResourceWork         = "work"
)

// Default token lifetimes.
const (
	DefaultAccessTokenTTL  = 15 * time.Minute
	DefaultRefreshTokenTTL = 30 * 24 * time.Hour
	DefaultSessionMaxAge   = 365 * 24 * time.Hour
)

// Lifetimes of the emailed recovery tokens (the emails quote them).
const (
	ResetTokenTTL        = time.Hour
	VerificationTokenTTL = 48 * time.Hour
)

// PasswordPolicy keeps LibraryZ's 8-character minimum (iam's DefaultPolicy
// asks for 12). The maximum bounds hashing cost.
var PasswordPolicy = password.Policy{MinLength: 8, MaxLength: 1024}

// Config configures New.
type Config struct {
	DB *gorm.DB

	// JWTSecret signs access tokens (HS256, at least 32 bytes).
	// JWTPreviousSecret, if set, still verifies them, so the secret can be
	// rotated with a rolling restart.
	JWTSecret         string
	JWTPreviousSecret string

	// AccessTokenTTL is the access-token lifetime. RefreshTokenTTL is how
	// long a session survives unused: every refresh restarts it, so an
	// active client stays signed in (as before iam). SessionMaxAge caps a
	// session's total lifetime regardless. Zero means the defaults above.
	AccessTokenTTL  time.Duration
	RefreshTokenTTL time.Duration
	SessionMaxAge   time.Duration

	// VerifySessionOnAccess checks on every request that the access token's
	// session still exists, so logout / logout-all / session revocation take
	// effect immediately instead of when the access token expires, at the
	// cost of one primary-key lookup per authenticated request.
	VerifySessionOnAccess bool

	// LoadSubjectOnAccess loads the user on every authenticated request, so
	// a granted or revoked role and a disabled or deleted user take effect
	// at once instead of when the access token expires (roles otherwise
	// come from the token). Two small lookups (users, user_roles) per
	// authenticated request. cmd/libraryz turns it on by default.
	LoadSubjectOnAccess bool

	// PerLogin and PerIP throttle failed logins. Nil means iam's in-memory
	// limiters, which are per-process: use a shared one (redisstore) when
	// running more than one instance.
	PerLogin ratelimit.Limiter
	PerIP    ratelimit.Limiter

	// TrustedProxies are the networks whose X-Forwarded-For is believed
	// when working out the client IP (Kong, load balancers). Without it,
	// behind a proxy every client shares the proxy's IP and the per-IP
	// login limit becomes global.
	TrustedProxies []netip.Prefix

	// Now is the clock (tests); nil means time.Now.
	Now func() time.Time
}

// Auth bundles the iam service and its HTTP adapter.
type Auth struct {
	Service  iam.Service
	HTTP     *httpauth.Middleware
	Users    *Users
	Sessions *Sessions
	Tokens   *Tokens
	// Recovery is password reset and email verification (iam.Recovery).
	Recovery iam.Recovery
	db       *gorm.DB

	// The login limiters iam throttles with. Kept so a reset request that
	// the per-account email cap suppresses still counts on iam's reset keys
	// (see countResetAttempt), answering exactly like any other request.
	perLogin, perIP ratelimit.Limiter
	now             func() time.Time

	accessTTL time.Duration
}

// New builds the auth layer.
func New(cfg Config) (*Auth, error) {
	if cfg.DB == nil {
		return nil, errors.New("auth: DB is required")
	}
	if cfg.AccessTokenTTL <= 0 {
		cfg.AccessTokenTTL = DefaultAccessTokenTTL
	}
	if cfg.RefreshTokenTTL <= 0 {
		cfg.RefreshTokenTTL = DefaultRefreshTokenTTL
	}
	if cfg.SessionMaxAge <= 0 {
		cfg.SessionMaxAge = DefaultSessionMaxAge
	}
	if cfg.SessionMaxAge < cfg.RefreshTokenTTL {
		cfg.SessionMaxAge = cfg.RefreshTokenTTL
	}

	kp, err := keyProvider(cfg.JWTSecret, cfg.JWTPreviousSecret)
	if err != nil {
		return nil, err
	}
	if cfg.Now == nil {
		cfg.Now = time.Now
	}
	jc := jwt.Config{Issuer: "libraryz", Audience: "libraryz-api", TTL: cfg.AccessTokenTTL, Now: cfg.Now}
	issuer, err := jwt.NewIssuer(kp, jc)
	if err != nil {
		return nil, err
	}
	verifier, err := jwt.NewVerifier(kp, jc)
	if err != nil {
		return nil, err
	}

	users := NewUsers(cfg.DB)
	hasher, err := password.NewArgon2id(password.DefaultParams, 0)
	if err != nil {
		return nil, err
	}
	passwords, err := password.NewProvider(users, hasher, PasswordPolicy)
	if err != nil {
		return nil, err
	}

	rbac, err := policy.NewRBAC(map[string][]policy.Permission{
		RoleModerator: {
			policy.P(ActionModerate, ResourceContribution),
			policy.P(ActionDelete, ResourceEdition),
			policy.P(ActionDelete, ResourceWork),
		},
	})
	if err != nil {
		return nil, err
	}

	// iam's own defaults, created here so they can be kept (see Auth).
	if cfg.PerLogin == nil && cfg.PerIP == nil {
		if cfg.PerLogin, err = ratelimit.NewMemory(ratelimit.Config{Threshold: 5}); err != nil {
			return nil, err
		}
		if cfg.PerIP, err = ratelimit.NewMemory(ratelimit.Config{Threshold: 100}); err != nil {
			return nil, err
		}
	}

	sessions := NewSessions(cfg.DB)
	tokens := NewTokens(cfg.DB)
	svc, err := iam.New(iam.Config{
		Providers: []provider.AuthProvider{passwords},
		Users:     users,
		Sessions:  sessions,
		Session: session.Config{
			Bearer: session.Timeouts{Idle: cfg.RefreshTokenTTL, Absolute: cfg.SessionMaxAge},
			Now:    cfg.Now,
		},
		AllowedModes:          []session.Mode{session.ModeBearer},
		TokenIssuer:           issuer,
		TokenVerifier:         verifier,
		VerifySessionOnAccess: cfg.VerifySessionOnAccess,
		LoadSubjectOnAccess:   cfg.LoadSubjectOnAccess,
		Policy:                rbac,
		Signup:                iam.SignupConfig{Policy: invite.Open},
		RateLimit:             iam.RateLimitConfig{PerLogin: cfg.PerLogin, PerIP: cfg.PerIP},
		// Reset requests share the login limiters (under different keys,
		// so they never lock anyone out of signing in).
		Recovery: iam.RecoveryConfig{
			Tokens:          tokens,
			ResetTTL:        ResetTokenTTL,
			VerificationTTL: VerificationTokenTTL,
		},
		Now: cfg.Now,
	})
	if err != nil {
		return nil, err
	}

	mw, err := httpauth.New(httpauth.Config{
		Service: svc,
		// Bearer only. With no cookie mode, httpauth (≥ v2.3.0) makes no
		// CSRF or cross-origin checks, so the Wasm client's cross-origin
		// requests work; browser access is governed by the CORS allowlist.
		Modes:          []session.Mode{session.ModeBearer},
		TrustedProxies: cfg.TrustedProxies,
		ErrorHandler:   writeError,
	})
	if err != nil {
		return nil, err
	}

	recovery, ok := svc.(iam.Recovery)
	if !ok {
		return nil, errors.New("auth: iam service does not implement Recovery")
	}

	return &Auth{
		Service: svc, HTTP: mw, Users: users, Sessions: sessions, Tokens: tokens, Recovery: recovery, db: cfg.DB,
		perLogin: cfg.PerLogin, perIP: cfg.PerIP, now: cfg.Now,
		accessTTL: cfg.AccessTokenTTL,
	}, nil
}

// keyProvider holds the signing key and, when rotating, the previous one
// for verification only.
func keyProvider(current, previous string) (*keys.MemoryProvider, error) {
	cur := hmacKey(current)
	if err := cur.ValidateForJWT(true); err != nil {
		return nil, err
	}
	if previous == "" {
		return keys.NewMemoryProvider(cur), nil
	}
	prev := hmacKey(previous)
	if err := prev.ValidateForJWT(false); err != nil {
		return nil, err
	}
	kp := keys.NewMemoryProvider(prev)
	if err := kp.Rotate(cur); err != nil {
		return nil, err
	}
	return kp, nil
}

// hmacKey derives the key id from the secret, so every replica configured
// with the same secret names it the same way without extra configuration.
func hmacKey(secret string) keys.Key {
	sum := sha256.Sum256([]byte("libraryz-kid:" + secret))
	return keys.Key{ID: hex.EncodeToString(sum[:4]), Alg: keys.HS256, Secret: []byte(secret)}
}

// writeError answers httpauth's rejections in plain text like the rest of
// the API; clients show 4xx bodies to users verbatim.
func writeError(w http.ResponseWriter, _ *http.Request, status int, err error) {
	msg := "unauthenticated"
	switch {
	case errors.Is(err, httpauth.ErrForbidden):
		// The only permissions LibraryZ checks are the moderator's.
		msg = "moderator required"
	case errors.Is(err, httpauth.ErrUnavailable):
		msg = "authentication temporarily unavailable"
	case status != http.StatusUnauthorized:
		msg = http.StatusText(status)
	}
	w.Header().Set("Cache-Control", "no-store")
	http.Error(w, msg, status)
}
