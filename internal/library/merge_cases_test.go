package library_test

import (
	"context"
	"encoding/json"
	"os"
	"reflect"
	"testing"

	"github.com/kararnab/libraryZ/internal/library"
)

// The merge cases the app runs too (LibraryMergeCasesTest.kt): the server
// and applyLocally must store the same thing.

// mergeFields are the UserBook fields the cases compare; started_at and
// finished_at are wall-clock, so they're left out.
var mergeFields = []string{
	"status", "shelf", "current_page", "total_pages", "progress_percent", "rating",
	"notes", "notes_other", "status_stamp", "progress_stamp", "rating_stamp", "shelf_stamp", "notes_stamp",
}

type mergeCase struct {
	Name  string `json:"name"`
	Steps []struct {
		Put json.RawMessage `json:"put"`
	} `json:"steps"`
	Want map[string]any `json:"want"`
}

// mergeView keeps mergeFields that aren't zero (missing, null, "" and 0 all are).
func mergeView(m map[string]any) map[string]any {
	out := map[string]any{}
	for _, k := range mergeFields {
		switch v := m[k]; v {
		case nil, "", 0.0:
		default:
			out[k] = v
		}
	}
	return out
}

func TestSharedMergeCases(t *testing.T) {
	raw, err := os.ReadFile("testdata/merge_cases.json")
	if err != nil {
		t.Fatal(err)
	}
	var file struct {
		Cases []mergeCase `json:"cases"`
	}
	if err := json.Unmarshal(raw, &file); err != nil {
		t.Fatal(err)
	}
	if len(file.Cases) == 0 {
		t.Fatal("no cases")
	}
	for _, c := range file.Cases {
		t.Run(c.Name, func(t *testing.T) {
			db := newTestDB(t)
			svc := library.NewService(db)
			wid := seedWork(t, db, "Dune", "Herbert")
			var ub *library.UserBook
			for i, s := range c.Steps {
				var req library.UpsertRequest
				if err := json.Unmarshal(s.Put, &req); err != nil {
					t.Fatalf("step %d: %v", i, err)
				}
				if ub, err = svc.Upsert(context.Background(), 1, wid, req); err != nil {
					t.Fatalf("step %d: %v", i, err)
				}
			}
			b, _ := json.Marshal(ub)
			var got map[string]any
			if err := json.Unmarshal(b, &got); err != nil {
				t.Fatal(err)
			}
			if g, w := mergeView(got), mergeView(c.Want); !reflect.DeepEqual(g, w) {
				t.Fatalf("got  %v\nwant %v", g, w)
			}
		})
	}
}
