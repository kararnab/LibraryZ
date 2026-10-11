package catalog

import (
	"fmt"
	"math"
	"strings"
	"time"
	"unicode/utf8"
)

// MaxFieldRunes caps each string field of a work, by its JSON name.
// Generous — these exist to stop abuse (megabyte-sized "titles"), not to
// police content. Creating a work and suggesting an edit to one
// (internal/contribution) both hold fields to them.
var MaxFieldRunes = map[string]int{
	"title":          500,
	"subtitle":       500,
	"authors":        1000,
	"description":    20000,
	"language":       64,
	"isbn":           32,
	"openlibrary_id": 64,
}

// MaxQueryRunes caps a search query: longer is no search a reader types.
const MaxQueryRunes = 200

// Sane bounds for publication_year: allows ancient texts (negative = BCE)
// and a little room for announced-but-unpublished works.
const (
	minPublicationYear = -5000
	maxYearAhead       = 5
)

// CheckWorkField reports what's wrong with v as the work field key (a JSON
// name from MaxFieldRunes, or "publication_year"), or nil. The error reads
// as a user-facing reason.
func CheckWorkField(key string, v any) error {
	if key == "publication_year" {
		year, ok := asInteger(v)
		if !ok {
			return fmt.Errorf("publication_year must be an integer")
		}
		maxYear := time.Now().Year() + maxYearAhead
		if year < minPublicationYear || year > int64(maxYear) {
			return fmt.Errorf("publication_year must be between %d and %d", minPublicationYear, maxYear)
		}
		return nil
	}
	str, ok := v.(string)
	if !ok {
		return fmt.Errorf("%s must be a string", key)
	}
	if key == "title" && strings.TrimSpace(str) == "" {
		return fmt.Errorf("title must not be empty")
	}
	if max, ok := MaxFieldRunes[key]; ok && utf8.RuneCountInString(str) > max {
		return fmt.Errorf("%s must be at most %d characters", key, max)
	}
	return nil
}

// asInteger accepts a whole number in any of the shapes a value can arrive
// in: float64 from encoding/json, or a Go int from in-process callers.
func asInteger(v any) (int64, bool) {
	switch n := v.(type) {
	case float64:
		if n != math.Trunc(n) || math.IsInf(n, 0) {
			return 0, false
		}
		return int64(n), true
	case int:
		return int64(n), true
	case int64:
		return n, true
	case int32:
		return int64(n), true
	}
	return 0, false
}
