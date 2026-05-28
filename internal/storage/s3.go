package storage

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"io/fs"
	"os"
	"time"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

// S3Config configures the S3/MinIO-backed Storage. Works against MinIO, AWS S3,
// Cloudflare R2, Backblaze B2 — anything S3-API-compatible.
type S3Config struct {
	Endpoint  string // host:port, no scheme (e.g. "minio:9000")
	AccessKey string
	SecretKey string
	Bucket    string
	UseSSL    bool
}

// S3 stores objects in an S3-compatible bucket, keyed by content sha256 (same
// content-addressing + dedup contract as Local). Durability is the bucket's
// responsibility — see PLAN.md ("Storage: do we need erasure coding?").
type S3 struct {
	client *minio.Client
	bucket string
}

// NewS3 builds the client and ensures the bucket exists. It retries the initial
// reach for ~30s so it tolerates being started alongside MinIO (compose) before
// MinIO is accepting connections.
func NewS3(ctx context.Context, cfg S3Config) (*S3, error) {
	client, err := minio.New(cfg.Endpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(cfg.AccessKey, cfg.SecretKey, ""),
		Secure: cfg.UseSSL,
	})
	if err != nil {
		return nil, fmt.Errorf("minio client: %w", err)
	}

	var lastErr error
	for attempt := 0; attempt < 15; attempt++ {
		exists, err := client.BucketExists(ctx, cfg.Bucket)
		if err == nil {
			if !exists {
				if mkErr := client.MakeBucket(ctx, cfg.Bucket, minio.MakeBucketOptions{}); mkErr != nil {
					return nil, fmt.Errorf("make bucket %q: %w", cfg.Bucket, mkErr)
				}
			}
			return &S3{client: client, bucket: cfg.Bucket}, nil
		}
		lastErr = err
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(2 * time.Second):
		}
	}
	return nil, fmt.Errorf("minio not reachable at %s: %w", cfg.Endpoint, lastErr)
}

// Put streams r to a temp file while hashing, so the object can be keyed by its
// content sha256 without buffering the whole (up to 500 MiB) blob in memory.
// Dedups: if an object with that key already exists, the upload is skipped.
func (s *S3) Put(ctx context.Context, r io.Reader) (Object, error) {
	tmp, err := os.CreateTemp("", "libraryz-upload-*")
	if err != nil {
		return Object{}, err
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName)

	h := sha256.New()
	size, copyErr := io.Copy(io.MultiWriter(tmp, h), r)
	if closeErr := tmp.Close(); closeErr != nil && copyErr == nil {
		copyErr = closeErr
	}
	if copyErr != nil {
		return Object{}, copyErr
	}
	sum := hex.EncodeToString(h.Sum(nil))

	exists, err := s.Exists(ctx, sum)
	if err != nil {
		return Object{}, err
	}
	if !exists {
		f, err := os.Open(tmpName)
		if err != nil {
			return Object{}, err
		}
		defer f.Close()
		if _, err := s.client.PutObject(ctx, s.bucket, sum, f, size, minio.PutObjectOptions{
			ContentType: "application/octet-stream",
		}); err != nil {
			return Object{}, err
		}
	}
	return Object{Key: sum, Size: size, SHA256: sum}, nil
}

func (s *S3) Get(ctx context.Context, key string) (io.ReadCloser, int64, error) {
	obj, err := s.client.GetObject(ctx, s.bucket, key, minio.GetObjectOptions{})
	if err != nil {
		return nil, 0, err
	}
	// GetObject is lazy; Stat now so a missing object surfaces here (and maps to
	// the same fs.ErrNotExist that Local.Get yields) rather than on first Read.
	// The Stat also gives us the authoritative object size to return.
	info, err := obj.Stat()
	if err != nil {
		_ = obj.Close()
		if minio.ToErrorResponse(err).Code == "NoSuchKey" {
			return nil, 0, fs.ErrNotExist
		}
		return nil, 0, err
	}
	return obj, info.Size, nil
}

func (s *S3) Delete(ctx context.Context, key string) error {
	return s.client.RemoveObject(ctx, s.bucket, key, minio.RemoveObjectOptions{})
}

func (s *S3) Exists(ctx context.Context, key string) (bool, error) {
	_, err := s.client.StatObject(ctx, s.bucket, key, minio.StatObjectOptions{})
	if err != nil {
		if minio.ToErrorResponse(err).Code == "NoSuchKey" {
			return false, nil
		}
		return false, err
	}
	return true, nil
}
