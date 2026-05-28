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

	"github.com/kararnab/libraryZ/internal/recommendation"
	"github.com/kararnab/libraryZ/internal/server"
	"github.com/kararnab/libraryZ/internal/storage"
	"github.com/kararnab/libraryZ/pkg/config"
	"github.com/kararnab/libraryZ/pkg/db"
	"gorm.io/gorm"
)

func main() {
	cfg := config.Load()
	if err := cfg.Validate(); err != nil {
		log.Fatalf("config: %v", err)
	}

	dbConn, err := db.InitDB(cfg.DatabaseURL, db.PoolConfig{
		MaxOpenConns:    cfg.DBMaxOpenConns,
		MaxIdleConns:    cfg.DBMaxIdleConns,
		ConnMaxLifetime: cfg.DBConnMaxLifetime,
	})
	if err != nil {
		log.Fatalf("db: %v", err)
	}
	if err := server.Migrate(dbConn); err != nil {
		log.Fatalf("migrate: %v", err)
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
	})

	startRecommendationTraining(dbConn, cfg)

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

// newStorage picks the blob backend implicitly from config: S3/MinIO when
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
	log.Printf("storage: local backend (dir=%s; set LIBRARYZ_S3_ENDPOINT to use S3/MinIO)", cfg.StorageDir)
	return storage.NewLocal(cfg.StorageDir)
}

// startRecommendationTraining trains the MF model once on startup (non-blocking
// — serving falls back to content/popularity until the first model lands) and
// re-trains on a ticker. In-process is fine until the matrix outgrows one pass;
// the scale path is a separate cmd/rectrain job.
func startRecommendationTraining(dbConn *gorm.DB, cfg *config.Config) {
	recCfg := recommendation.DefaultConfig()
	if cfg.RecFactors > 0 {
		recCfg.Factors = cfg.RecFactors
	}
	if cfg.RecAlpha > 0 {
		recCfg.Alpha = cfg.RecAlpha
	}
	trainer := recommendation.NewTrainer(dbConn, recCfg)

	train := func() {
		if err := trainer.Train(context.Background()); err != nil {
			log.Printf("rec: train failed: %v", err)
		} else {
			log.Printf("rec: model retrained")
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
