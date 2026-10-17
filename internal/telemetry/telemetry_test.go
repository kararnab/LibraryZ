package telemetry

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/glebarez/sqlite"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

func TestDefaultCatalogParses(t *testing.T) {
	c, err := DefaultCatalog()
	if err != nil {
		t.Fatal(err)
	}
	if len(c.Events) == 0 {
		t.Fatal("empty catalog")
	}
}

func TestParseCatalogRefusesOpenProps(t *testing.T) {
	for name, doc := range map[string]string{
		"free text":      `{"events":[{"name":"a","props":[{"name":"q","type":"string"}]}]}`,
		"enum no values": `{"events":[{"name":"a","props":[{"name":"q","type":"enum"}]}]}`,
		"int no bounds":  `{"events":[{"name":"a","props":[{"name":"n","type":"int"}]}]}`,
		"camelCase":      `{"events":[{"name":"bookOpened","props":[]}]}`,
		"twice":          `{"events":[{"name":"a","props":[]},{"name":"a","props":[]}]}`,
		"value spaces":   `{"events":[{"name":"a","props":[{"name":"q","type":"enum","values":["two words"]}]}]}`,
	} {
		if _, err := ParseCatalog([]byte(doc)); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
}

func TestCheckInt(t *testing.T) {
	c, err := ParseCatalog([]byte(`{"events":[{"name":"a","props":[{"name":"n","type":"int","min":0,"max":10}]}]}`))
	if err != nil {
		t.Fatal(err)
	}
	for raw, ok := range map[string]bool{"0": true, "10": true, "11": false, "-1": false, "1.5": false, `"3"`: false, "null": false} {
		_, err := c.Check("a", map[string]json.RawMessage{"n": json.RawMessage(raw)})
		if (err == nil) != ok {
			t.Errorf("n=%s: err %v, want ok=%v", raw, err, ok)
		}
	}
}

func TestCleanException(t *testing.T) {
	for in, want := range map[string]string{
		"java.io.IOException: /home/x/Emma.epub": "java.io.IOException",
		"TypeError":                              "TypeError",
		"  kotlin.IllegalStateException  ":       "kotlin.IllegalStateException",
		"“Emma” failed":                          "Unknown",
		"":                                       "Unknown",
	} {
		if got := cleanException(in); got != want {
			t.Errorf("cleanException(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestCleanStackBounds(t *testing.T) {
	frames := make([]string, MaxFrames+10)
	for i := range frames {
		frames[i] = "at f" + strings.Repeat("x", MaxFrameRunes)
	}
	got := cleanStack(frames)
	if len(got) != MaxFrames {
		t.Errorf("%d frames, want %d", len(got), MaxFrames)
	}
	if n := len([]rune(got[0])); n != MaxFrameRunes {
		t.Errorf("frame of %d runes, want %d", n, MaxFrameRunes)
	}
	got = cleanStack([]string{"at a(" + uuid.NewString() + ")", "at b(eyJhbGciOi.eyJzdWIi.c2ln)", "at c(C:\\Users\\Arnab\\x.kt:1)"})
	want := []string{"at a(<id>)", "at b(<token>)", "at c(~\\x.kt:1)"}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("frame %d = %q, want %q", i, got[i], want[i])
		}
	}
}

func TestPurge(t *testing.T) {
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	if err != nil {
		t.Fatal(err)
	}
	if err := db.AutoMigrate(&CrashGroup{}, &CrashReport{}, &EventRecord{}); err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC()
	old, recent := now.Add(-100*24*time.Hour), now.Add(-time.Hour)
	sink := StoreSink{DB: db}
	client := Client{Platform: "android", AppVersion: "1.0"}
	for _, at := range []time.Time{old, recent} {
		stack := []string{"at x" + at.String()}
		if err := sink.WriteCrashes(context.Background(), []Crash{{
			Client: client, Kind: "fatal", Exception: "E", Stack: stack,
			Fingerprint: fingerprint("android", "E", stack), OccurredAt: at, ReceivedAt: at,
		}}); err != nil {
			t.Fatal(err)
		}
		if err := sink.WriteEvents(context.Background(), []Event{{
			Client: client, Name: "app_opened", Props: map[string]any{},
			InstallID: uuid.NewString(), SessionID: uuid.NewString(), OccurredAt: at, ReceivedAt: at,
		}}); err != nil {
			t.Fatal(err)
		}
	}
	n, err := Purge(context.Background(), db, now.Add(-90*24*time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	if n != 3 {
		t.Errorf("purged %d rows, want 3 (a report, an event, a group)", n)
	}
	for _, m := range []any{&CrashGroup{}, &CrashReport{}, &EventRecord{}} {
		var c int64
		db.Model(m).Count(&c)
		if c != 1 {
			t.Errorf("%T: %d left, want 1", m, c)
		}
	}
}
