package storage

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"io/fs"
	"os"
	"path/filepath"
)

// Local stores objects on the local filesystem under root, sharded by the
// first two hex chars of the sha256 to keep directories small.
type Local struct {
	root string
}

func NewLocal(root string) (*Local, error) {
	if err := os.MkdirAll(root, 0o755); err != nil {
		return nil, err
	}
	return &Local{root: root}, nil
}

func (l *Local) path(key string) string {
	return filepath.Join(l.root, key[:2], key)
}

func (l *Local) Put(_ context.Context, r io.Reader) (Object, error) {
	tmp, err := os.CreateTemp(l.root, ".upload-*")
	if err != nil {
		return Object{}, err
	}
	tmpName := tmp.Name()

	h := sha256.New()
	size, copyErr := io.Copy(io.MultiWriter(tmp, h), r)
	closeErr := tmp.Close()
	if copyErr != nil {
		_ = os.Remove(tmpName)
		return Object{}, copyErr
	}
	if closeErr != nil {
		_ = os.Remove(tmpName)
		return Object{}, closeErr
	}

	sum := hex.EncodeToString(h.Sum(nil))
	dest := l.path(sum)
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		_ = os.Remove(tmpName)
		return Object{}, err
	}

	if _, err := os.Stat(dest); err == nil {
		// Dedupe: identical bytes already on disk.
		_ = os.Remove(tmpName)
	} else if errors.Is(err, fs.ErrNotExist) {
		if err := os.Rename(tmpName, dest); err != nil {
			_ = os.Remove(tmpName)
			return Object{}, err
		}
	} else {
		_ = os.Remove(tmpName)
		return Object{}, err
	}

	return Object{Key: sum, Size: size, SHA256: sum}, nil
}

func (l *Local) Get(_ context.Context, key string) (io.ReadCloser, int64, error) {
	f, err := os.Open(l.path(key))
	if err != nil {
		return nil, 0, err
	}
	info, err := f.Stat()
	if err != nil {
		_ = f.Close()
		return nil, 0, err
	}
	return f, info.Size(), nil
}

func (l *Local) Delete(_ context.Context, key string) error {
	err := os.Remove(l.path(key))
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	return err
}

func (l *Local) Exists(_ context.Context, key string) (bool, error) {
	_, err := os.Stat(l.path(key))
	if err == nil {
		return true, nil
	}
	if errors.Is(err, fs.ErrNotExist) {
		return false, nil
	}
	return false, err
}
