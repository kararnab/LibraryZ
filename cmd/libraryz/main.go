package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/kararnab/libraryZ/internal/catalog"
	"github.com/kararnab/libraryZ/internal/migrations"
	"github.com/kararnab/libraryZ/internal/recommendation"
	"github.com/kararnab/libraryZ/internal/runlock"
	"github.com/kararnab/libraryZ/internal/server"
	"github.com/kararnab/libraryZ/internal/storage"
	"github.com/kararnab/libraryZ/pkg/config"
	"github.com/kararnab/libraryZ/pkg/db"
	"gorm.io/gorm"
)

func main() {
	cfg := config.Load()

	// `libraryz migrate` applies pending schema migrations and exits — for
	// running them as a one-shot deploy step (with LIBRARYZ_AUTO_MIGRATE=false
	// on the servers) instead of on every instance's startup.
	if len(os.Args) > 1 && os.Args[1] == "migrate" {
		if err := migrate(cfg); err != nil {
			log.Fatalf("migrate: %v", err)
		}
		log.Printf("migrations up to date")
		return
	}

	if err := cfg.Validate(); err != nil {
		log.Fatalf("config: %v", err)
	}

	if cfg.AutoMigrate {
		if err := migrate(cfg); err != nil {
			log.Fatalf("migrate: %v", err)
		}
	}

	dbConn, err := db.InitDB(cfg.DatabaseURL, db.PoolConfig{
		MaxOpenConns:    cfg.DBMaxOpenConns,
		MaxIdleConns:    cfg.DBMaxIdleConns,
		ConnMaxLifetime: cfg.DBConnMaxLifetime,
	})
	if err != nil {
		log.Fatalf("db: %v", err)
	}

	store, err := newStorage(cfg)
	if err != nil {
		log.Fatalf("storage: %v", err)
	}

	h := server.New(server.Deps{
		DB:              dbConn,
		Storage:         store,
		MaxUploadBytes:  cfg.MaxUploadBytes,
		AllowedOrigins:  cfg.AllowedOrigins,
		AllowPrivateLAN: cfg.CORSAllowPrivateLAN,
		AccessTokenTTL:  cfg.AccessTokenTTL,
		RefreshTokenTTL: cfg.RefreshTokenTTL,
	})

	startRecommendationTraining(dbConn, cfg)
	startBlobGC(catalog.NewService(dbConn, store), cfg)

	srv := &http.Server{
		Addr:              cfg.ListenAddr,
		Handler:           h,
		ReadHeaderTimeout: cfg.ReadHeaderTimeout,
		ReadTimeout:       cfg.ReadTimeout,
		WriteTimeout:      cfg.WriteTimeout,
		IdleTimeout:       cfg.IdleTimeout,
	}

	// Serve until SIGINT/SIGTERM, then drain in-flight requests so an LB-driven
	// rolling deploy doesn't sever live connections.
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	serveErr := make(chan error, 1)
	go func() {
		log.Printf("libraryz listening on %s", cfg.ListenAddr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			serveErr <- err
		}
	}()

	select {
	case err := <-serveErr:
		log.Fatalf("serve: %v", err)
	case <-ctx.Done():
		log.Printf("shutdown signal received; draining (timeout %s)", cfg.ShutdownTimeout)
		shutCtx, cancel := context.WithTimeout(context.Background(), cfg.ShutdownTimeout)
		defer cancel()
		if err := srv.Shutdown(shutCtx); err != nil {
			log.Fatalf("graceful shutdown failed: %v", err)
		}
		log.Printf("shutdown complete")
	}
}

// migrate runs schema migrations over a short-lived connection to
// MigrateDatabaseURL (direct Postgres — see config.MigrateDatabaseURL).
// Concurrent instances are serialized by an advisory lock in migrations.Up.
func migrate(cfg *config.Config) error {
	conn, err := db.InitDB(cfg.MigrateDatabaseURL, db.PoolConfig{MaxOpenConns: 2, MaxIdleConns: 1, ConnMaxLifetime: time.Minute})
	if err != nil {
		return err
	}
	if sqlDB, err := conn.DB(); err == nil {
		defer sqlDB.Close()
	}
	return migrations.Up(context.Background(), conn)
}

// newStorage picks the blob backend implicitly from config: S3 when
// LIBRARYZ_S3_ENDPOINT is set (production path — docker-compose, real envs),
// else the local filesystem under LIBRARYZ_STORAGE_DIR (host-mode dev
// fallback; also the backend used by smoke tests via storage.NewLocal). There
// is no explicit selector env var — setting the S3 endpoint is the signal.
func newStorage(cfg *config.Config) (storage.Storage, error) {
	if cfg.S3Endpoint != "" {
		log.Printf("storage: s3 backend (endpoint=%s bucket=%s)", cfg.S3Endpoint, cfg.S3Bucket)
		return storage.NewS3(context.Background(), storage.S3Config{
			Endpoint:  cfg.S3Endpoint,
			AccessKey: cfg.S3AccessKey,
			SecretKey: cfg.S3SecretKey,
			Bucket:    cfg.S3Bucket,
			UseSSL:    cfg.S3UseSSL,
		})
	}
	log.Printf("storage: local backend (dir=%s; set LIBRARYZ_S3_ENDPOINT to use S3)", cfg.StorageDir)
	return storage.NewLocal(cfg.StorageDir)
}

// startBlobGC sweeps orphaned and long-removed blobs on a ticker (not at
// startup — there's no urgency, and it keeps rolling restarts cheap). Safe on
// every replica: CollectGarbage holds a cluster-wide lock and skips if busy.
func startBlobGC(svc *catalog.Service, cfg *config.Config) {
	go func() {
		ticker := time.NewTicker(cfg.BlobGCInterval)
		defer ticker.Stop()
		for range ticker.C {
			stats, err := svc.CollectGarbage(context.Background(), cfg.BlobGCRetention)
			switch {
			case errors.Is(err, runlock.ErrBusy):
				log.Printf("blob gc: another instance is sweeping; skipped")
			case err != nil:
				log.Printf("blob gc: failed after deleting %d blobs: %v", stats.Deleted, err)
			default:
				log.Printf("blob gc: scanned %d, deleted %d", stats.Scanned, stats.Deleted)
			}
		}
	}()
}

// startRecommendationTraining trains the MF model once on startup (non-blocking
// — serving falls back to content/popularity until the first model lands) and
// re-trains on a ticker. Safe to run on every replica (see Trainer.Train). In-process is fine until the matrix outgrows one pass;
// the scale path is a separate cmd/rectrain job.
func startRecommendationTraining(dbConn *gorm.DB, cfg *config.Config) {
	recCfg := recommendation.DefaultConfig()
	if cfg.RecFactors > 0 {
		recCfg.Factors = cfg.RecFactors
	}
	if cfg.RecAlpha > 0 {
		recCfg.Alpha = cfg.RecAlpha
	}
	// Every instance runs this loop, but Train holds a cluster-wide lock and
	// skips if another instance trained within half an interval — so with N
	// replicas the model is still trained ~once per interval, by whichever
	// instance gets there first, with no single designated trainer to lose.
	recCfg.MinRetrainAge = cfg.RecRetrainInterval / 2
	trainer := recommendation.NewTrainer(dbConn, recCfg)

	train := func() {
		switch err := trainer.Train(context.Background()); {
		case err == nil:
			log.Printf("rec: model retrained")
		case errors.Is(err, runlock.ErrBusy):
			log.Printf("rec: another instance is training; skipped")
		case errors.Is(err, recommendation.ErrFresh):
			log.Printf("rec: model is fresh; skipped")
		default:
			log.Printf("rec: train failed: %v", err)
		}
	}
	go func() {
		train()
		ticker := time.NewTicker(cfg.RecRetrainInterval)
		defer ticker.Stop()
		for range ticker.C {
			train()
		}
	}()
}
