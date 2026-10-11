package library_test

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/kararnab/libraryZ/internal/library"
)

// at is a stamp t minutes after a fixed recent moment, from device d.
func at(t int, d string) string {
	base := time.Now().Add(-time.Hour).UnixMilli()
	return fmt.Sprintf("%013d.0000.%s", base+int64(t)*60_000, d)
}

func TestNewestStampWinsPerGroupInAnyOrder(t *testing.T) {
	db := newTestDB(t)
	svc := library.NewService(db)
	wid := seedWork(t, db, "Dune", "Herbert")
	ctx := context.Background()

	// Phone (offline) read to p.40 at t=10 and rated at t=12; desktop read to
	// p.80 at t=20. The phone syncs last.
	if _, err := svc.Upsert(ctx, 1, wid, library.UpsertRequest{CurrentPage: intptr(80), Stamps: library.Stamps{Progress: at(20, "desk")}}); err != nil {
		t.Fatal(err)
	}
	ub, err := svc.Upsert(ctx, 1, wid, library.UpsertRequest{
		CurrentPage: intptr(40), Rating: intptr(4),
		Stamps: library.Stamps{Progress: at(10, "phone"), Rating: at(12, "phone")},
	})
	if err != nil {
		t.Fatal(err)
	}
	if ub.CurrentPage != 80 || ub.Rating == nil || *ub.Rating != 4 {
		t.Fatalf("want page 80 (newer) and rating 4 (only write), got page %d rating %v", ub.CurrentPage, ub.Rating)
	}
	// Replaying the same write changes nothing.
	again, _ := svc.Upsert(ctx, 1, wid, library.UpsertRequest{CurrentPage: intptr(40), Stamps: library.Stamps{Progress: at(10, "phone")}})
	if again.CurrentPage != 80 {
		t.Fatalf("replay applied: page %d", again.CurrentPage)
	}
}

func TestReadForcesFullProgressOnlyIfNewer(t *testing.T) {
	db := newTestDB(t)
	svc := library.NewService(db)
	wid := seedWork(t, db, "Dune", "Herbert")
	ctx := context.Background()
	svc.Upsert(ctx, 1, wid, library.UpsertRequest{ProgressPercent: intptr(30), Stamps: library.Stamps{Progress: at(20, "a")}})
	ub, _ := svc.Upsert(ctx, 1, wid, library.UpsertRequest{Status: strptr("read"), Stamps: library.Stamps{Status: at(10, "b")}})
	if ub.Status != library.StatusRead || ub.ProgressPercent != 30 {
		t.Fatalf("older read must not override newer progress: %s %d", ub.Status, ub.ProgressPercent)
	}
}

func TestRemovalBeatsOlderChangesButNotNewer(t *testing.T) {
	db := newTestDB(t)
	svc := library.NewService(db)
	wid := seedWork(t, db, "Dune", "Herbert")
	ctx := context.Background()
	svc.Upsert(ctx, 1, wid, library.UpsertRequest{Status: strptr("reading"), Stamps: library.Stamps{Status: at(5, "a")}})
	if err := svc.Delete(ctx, 1, wid, at(20, "a")); err != nil {
		t.Fatal(err)
	}
	// An offline change from before the removal doesn't bring it back.
	if _, err := svc.Upsert(ctx, 1, wid, library.UpsertRequest{CurrentPage: intptr(9), Stamps: library.Stamps{Progress: at(15, "b")}}); !errors.Is(err, library.ErrNotFound) {
		t.Fatalf("older change after removal: err = %v, want ErrNotFound", err)
	}
	if _, err := svc.Get(ctx, 1, wid); !errors.Is(err, library.ErrNotFound) {
		t.Fatal("entry came back")
	}
	// A newer one does, without the older groups.
	ub, err := svc.Upsert(ctx, 1, wid, library.UpsertRequest{Rating: intptr(5), CurrentPage: intptr(3), Stamps: library.Stamps{Rating: at(25, "b"), Progress: at(15, "b")}})
	if err != nil {
		t.Fatal(err)
	}
	if ub.Status != library.StatusWant || ub.CurrentPage != 0 || ub.Rating == nil {
		t.Fatalf("re-added entry wrong: %+v", ub)
	}
	// A removal stamped before the entry's newest change keeps it.
	if err := svc.Delete(ctx, 1, wid, at(22, "c")); err != nil {
		t.Fatal(err)
	}
	if _, err := svc.Get(ctx, 1, wid); err != nil {
		t.Fatalf("older removal deleted a newer entry: %v", err)
	}
}

func TestNotesEditedOnTwoDevicesKeepsBoth(t *testing.T) {
	db := newTestDB(t)
	svc := library.NewService(db)
	wid := seedWork(t, db, "Dune", "Herbert")
	ctx := context.Background()
	base, _ := svc.Upsert(ctx, 1, wid, library.UpsertRequest{Notes: strptr("v1"), Stamps: library.Stamps{Notes: at(1, "a")}})

	// Both edit from v1 offline; the newer wins, the other is kept.
	svc.Upsert(ctx, 1, wid, library.UpsertRequest{Notes: strptr("desk"), NotesBase: &base.NotesStamp, Stamps: library.Stamps{Notes: at(10, "desk")}})
	ub, _ := svc.Upsert(ctx, 1, wid, library.UpsertRequest{Notes: strptr("phone"), NotesBase: &base.NotesStamp, Stamps: library.Stamps{Notes: at(5, "phone")}})
	if ub.Notes != "desk" || ub.NotesOther != "phone" {
		t.Fatalf("notes = %q other = %q", ub.Notes, ub.NotesOther)
	}
	// Choosing clears the other text; an edit that saw the latest isn't a conflict.
	ub, _ = svc.Upsert(ctx, 1, wid, library.UpsertRequest{Notes: strptr("both"), NotesBase: &ub.NotesStamp, ResolveNotes: true, Stamps: library.Stamps{Notes: at(30, "phone")}})
	if ub.Notes != "both" || ub.NotesOther != "" {
		t.Fatalf("after resolve: notes = %q other = %q", ub.Notes, ub.NotesOther)
	}
}

func TestStampsAreCheckedAndFutureOnesPulledBack(t *testing.T) {
	db := newTestDB(t)
	svc := library.NewService(db)
	wid := seedWork(t, db, "Dune", "Herbert")
	ctx := context.Background()
	if _, err := svc.Upsert(ctx, 1, wid, library.UpsertRequest{Rating: intptr(3), Stamps: library.Stamps{Rating: "yesterday"}}); !errors.Is(err, library.ErrInvalidStamp) {
		t.Fatalf("bad stamp: %v", err)
	}
	// A clock a day fast doesn't win for a day.
	future := fmt.Sprintf("%013d.0000.fast", time.Now().Add(24*time.Hour).UnixMilli())
	svc.Upsert(ctx, 1, wid, library.UpsertRequest{Rating: intptr(1), Stamps: library.Stamps{Rating: future}})
	time.Sleep(2 * time.Millisecond)
	ub, _ := svc.Upsert(ctx, 1, wid, library.UpsertRequest{Rating: intptr(5)})
	if *ub.Rating != 5 {
		t.Fatalf("future stamp kept winning: rating %d", *ub.Rating)
	}
}
