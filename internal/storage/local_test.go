package storage

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLocalPutGetRoundTrip(t *testing.T) {
	store, _ := NewLocal(t.TempDir())
	payload := []byte("hello libraryz")
	want := sha256.Sum256(payload)
	wantHex := hex.EncodeToString(want[:])

	obj, err := store.Put(context.Background(), bytes.NewReader(payload))
	if err != nil {
		t.Fatalf("put: %v", err)
	}
	if obj.SHA256 != wantHex {
		t.Fatalf("sha256 mismatch: got %s want %s", obj.SHA256, wantHex)
	}
	if obj.Size != int64(len(payload)) {
		t.Fatalf("size mismatch: got %d want %d", obj.Size, len(payload))
	}

	rc, size, err := store.Get(context.Background(), obj.Key)
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	defer rc.Close()
	if size != int64(len(payload)) {
		t.Fatalf("get size mismatch: got %d want %d", size, len(payload))
	}
	got, _ := io.ReadAll(rc)
	if !bytes.Equal(got, payload) {
		t.Fatalf("bytes mismatch")
	}
}

func TestLocalPutDedups(t *testing.T) {
	root := t.TempDir()
	store, _ := NewLocal(root)
	payload := []byte("repeat me")

	first, err := store.Put(context.Background(), bytes.NewReader(payload))
	if err != nil {
		t.Fatalf("first put: %v", err)
	}
	second, err := store.Put(context.Background(), bytes.NewReader(payload))
	if err != nil {
		t.Fatalf("second put: %v", err)
	}
	if first.Key != second.Key {
		t.Fatalf("dedup: different keys: %s vs %s", first.Key, second.Key)
	}

	// There must be exactly one blob on disk (plus the shard dir), and no
	// leftover ".upload-*" temp files.
	var blobs, temps int
	_ = filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
		if err != nil || info.IsDir() {
			return err
		}
		base := filepath.Base(path)
		if strings.HasPrefix(base, ".upload-") {
			temps++
		} else {
			blobs++
		}
		return nil
	})
	if blobs != 1 {
		t.Fatalf("expected 1 blob on disk, found %d", blobs)
	}
	if temps != 0 {
		t.Fatalf("found %d leftover temp files", temps)
	}
}

func TestLocalExistsAndDelete(t *testing.T) {
	store, _ := NewLocal(t.TempDir())
	obj, _ := store.Put(context.Background(), bytes.NewReader([]byte("x")))

	ok, err := store.Exists(context.Background(), obj.Key)
	if err != nil || !ok {
		t.Fatalf("Exists after put: ok=%v err=%v", ok, err)
	}
	if err := store.Delete(context.Background(), obj.Key); err != nil {
		t.Fatalf("delete: %v", err)
	}
	ok, _ = store.Exists(context.Background(), obj.Key)
	if ok {
		t.Fatalf("Exists after delete should be false")
	}
	// Deleting a missing key is a no-op.
	if err := store.Delete(context.Background(), obj.Key); err != nil {
		t.Fatalf("delete-missing should be nil: %v", err)
	}
}
