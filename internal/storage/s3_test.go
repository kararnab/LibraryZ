package storage

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"io/fs"
	"os"
	"testing"

	"github.com/google/uuid"
)

// s3TestConfig reads connection details from the environment; the test skips
// unless LIBRARYZ_S3_ENDPOINT is set, so default `go test` (and CI) don't need
// MinIO. Run against the compose MinIO with, e.g.:
//
//	LIBRARYZ_S3_ENDPOINT=localhost:9000 LIBRARYZ_S3_ACCESS_KEY=minioadmin \
//	LIBRARYZ_S3_SECRET_KEY=minioadmin go test ./internal/storage/ -run S3 -v
func s3TestConfig(t *testing.T) S3Config {
	t.Helper()
	ep := os.Getenv("LIBRARYZ_S3_ENDPOINT")
	if ep == "" {
		t.Skip("set LIBRARYZ_S3_ENDPOINT to run S3 storage tests")
	}
	getOr := func(k, def string) string {
		if v := os.Getenv(k); v != "" {
			return v
		}
		return def
	}
	return S3Config{
		Endpoint:  ep,
		AccessKey: getOr("LIBRARYZ_S3_ACCESS_KEY", "minioadmin"),
		SecretKey: getOr("LIBRARYZ_S3_SECRET_KEY", "minioadmin"),
		// Unique bucket per run so parallel/repeat runs don't collide.
		Bucket: "libraryz-test-" + uuid.NewString()[:8],
		UseSSL: os.Getenv("LIBRARYZ_S3_USE_SSL") == "true",
	}
}

func TestS3RoundTripAndDedup(t *testing.T) {
	cfg := s3TestConfig(t)
	ctx := context.Background()
	s, err := NewS3(ctx, cfg)
	if err != nil {
		t.Fatalf("NewS3: %v", err)
	}

	payload := []byte("a fake epub body for the s3 round-trip test\n")
	want := sha256.Sum256(payload)
	wantHex := hex.EncodeToString(want[:])

	obj, err := s.Put(ctx, bytes.NewReader(payload))
	if err != nil {
		t.Fatalf("Put: %v", err)
	}
	if obj.SHA256 != wantHex || obj.Key != wantHex {
		t.Fatalf("key/sha mismatch: got key=%s sha=%s want %s", obj.Key, obj.SHA256, wantHex)
	}
	if obj.Size != int64(len(payload)) {
		t.Fatalf("size = %d, want %d", obj.Size, len(payload))
	}

	// Exists.
	ok, err := s.Exists(ctx, obj.Key)
	if err != nil || !ok {
		t.Fatalf("Exists = %v, %v; want true", ok, err)
	}

	// Get round-trips the exact bytes.
	rc, size, err := s.Get(ctx, obj.Key)
	if err != nil {
		t.Fatalf("Get: %v", err)
	}
	if size != int64(len(payload)) {
		t.Fatalf("Get size = %d, want %d", size, len(payload))
	}
	got, _ := io.ReadAll(rc)
	_ = rc.Close()
	if !bytes.Equal(got, payload) {
		t.Fatalf("round-trip mismatch (got %d bytes)", len(got))
	}

	// Dedup: re-Put identical bytes → same key, still one object.
	obj2, err := s.Put(ctx, bytes.NewReader(payload))
	if err != nil {
		t.Fatalf("Put (dedup): %v", err)
	}
	if obj2.Key != obj.Key {
		t.Fatalf("dedup: second key %s != first %s", obj2.Key, obj.Key)
	}

	// Delete, then Exists is false and Get yields fs.ErrNotExist.
	if err := s.Delete(ctx, obj.Key); err != nil {
		t.Fatalf("Delete: %v", err)
	}
	ok, _ = s.Exists(ctx, obj.Key)
	if ok {
		t.Fatalf("Exists after Delete = true")
	}
	if _, _, err := s.Get(ctx, obj.Key); !errors.Is(err, fs.ErrNotExist) {
		t.Fatalf("Get after Delete = %v, want fs.ErrNotExist", err)
	}
}
