package storage

import (
	"context"
	"errors"
	"io"
	"time"
)

// ErrInvalidKey is returned for a key that isn't a sha256 content address
// (64 lowercase hex chars). Backends check this before touching the
// filesystem or bucket so a bad key from a drifted DB row fails cleanly.
var ErrInvalidKey = errors.New("storage: invalid object key")

// validKey reports whether key is a sha256 hex digest, the only key shape
// Put ever produces.
func validKey(key string) bool {
	if len(key) != 64 {
		return false
	}
	for i := 0; i < len(key); i++ {
		c := key[i]
		if (c < '0' || c > '9') && (c < 'a' || c > 'f') {
			return false
		}
	}
	return true
}

// Object is the metadata returned after writing to storage.
type Object struct {
	Key    string // backend-defined; for Local it is the sha256 hex
	Size   int64
	SHA256 string
}

// Storage is a content-addressable blob interface. Implementations are
// responsible for durability — callers do not request replication or
// erasure-coding. See PLAN.md ("Storage: do we need erasure coding?").
type Storage interface {
	// Put streams r to storage, computing sha256 in flight, and deduplicates
	// by content hash. Returns the resulting Object whose Key can be used
	// with Get.
	Put(ctx context.Context, r io.Reader) (Object, error)
	// Get opens the object for key and reports its size in bytes, so callers
	// can set a Content-Length that matches the bytes they're about to stream
	// rather than trusting a separately-stored size that may have drifted.
	// Returns fs.ErrNotExist if the key is absent. Get, Delete and Exists
	// return ErrInvalidKey for a key that isn't a sha256 hex digest.
	Get(ctx context.Context, key string) (io.ReadCloser, int64, error)
	Delete(ctx context.Context, key string) error
	Exists(ctx context.Context, key string) (bool, error)
	// List calls fn for every stored object, in no particular order, stopping
	// at the first error fn returns. Used by blob garbage collection.
	List(ctx context.Context, fn func(ObjectInfo) error) error
}

// ObjectInfo describes a stored object as reported by List.
type ObjectInfo struct {
	Key     string
	Size    int64
	ModTime time.Time
}
