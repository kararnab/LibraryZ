package server

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"strings"
	"sync/atomic"
	"time"

	"github.com/kararnab/libraryZ/internal/storage"
	"gorm.io/gorm"
)

// readyTimeout bounds each dependency check so a hung dependency makes the
// probe fail fast instead of piling up probe requests.
const readyTimeout = 2 * time.Second

// readinessSentinelKey is probed with Storage.Exists. It must be a valid
// sha256-shaped key (backends reject anything else with ErrInvalidKey) but is
// all zeros, so it never actually exists; the point is that the call reaches
// the backend (for S3 a missing bucket or bad credentials error out, while a
// missing key is a clean false).
var readinessSentinelKey = strings.Repeat("0", 64)

// ReadyResponse is the body of GET /ready.
type ReadyResponse struct {
	Status string            `json:"status"`           // "ready" | "unavailable" | "draining"
	Checks map[string]string `json:"checks,omitempty"` // dependency -> "ok" | "unavailable"
}

// ready is the readiness probe: unlike /health (liveness — process is up),
// it checks that the database and blob storage are reachable, answering 503
// if either isn't so a load balancer stops routing here until they recover.
// Kept separate from /health on purpose: a liveness probe that depends on
// the DB turns a DB blip into a restart storm.
//
// Failure details are logged, not returned — the endpoint is reachable
// through the public edge.
//
// Once draining is set (shutdown has begun), it answers 503 "draining"
// without checking anything, so the LB takes the instance out of rotation
// while it still serves what it's sent.
func ready(db *gorm.DB, store storage.Storage, draining *atomic.Bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if draining != nil && draining.Load() {
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("Cache-Control", "no-store")
			w.WriteHeader(http.StatusServiceUnavailable)
			_ = json.NewEncoder(w).Encode(ReadyResponse{Status: "draining"})
			return
		}
		checks := map[string]func(context.Context) error{
			"database": func(ctx context.Context) error {
				sqlDB, err := db.DB()
				if err != nil {
					return err
				}
				return sqlDB.PingContext(ctx)
			},
			"storage": func(ctx context.Context) error {
				_, err := store.Exists(ctx, readinessSentinelKey)
				return err
			},
		}
		resp := ReadyResponse{Status: "ready", Checks: make(map[string]string, len(checks))}
		code := http.StatusOK
		for name, check := range checks {
			ctx, cancel := context.WithTimeout(r.Context(), readyTimeout)
			err := check(ctx)
			cancel()
			if err != nil {
				log.Printf("ready: %s check failed: %v", name, err)
				resp.Checks[name] = "unavailable"
				resp.Status = "unavailable"
				code = http.StatusServiceUnavailable
				continue
			}
			resp.Checks[name] = "ok"
		}
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(code)
		_ = json.NewEncoder(w).Encode(resp)
	}
}
