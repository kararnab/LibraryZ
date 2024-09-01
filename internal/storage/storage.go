package storage

import (
	"context"
	"io"
)

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
	Get(ctx context.Context, key string) (io.ReadCloser, error)
	Delete(ctx context.Context, key string) error
	Exists(ctx context.Context, key string) (bool, error)
}
