package telemetry

import (
	"encoding/json"
	"errors"
	"net/http"
	"regexp"
	"time"

	"github.com/google/uuid"
	"github.com/kararnab/libraryZ/internal/httpx"
	"github.com/kararnab/libraryZ/internal/obs"
	"github.com/prometheus/client_golang/prometheus"
)

// Request bounds. A batch past them is refused whole (400 or 413).
const (
	MaxCrashBody   = 256 << 10
	MaxEventBody   = 64 << 10
	MaxCrashBatch  = 10
	MaxEventBatch  = 100
	maxClockSkew   = 5 * time.Minute
	maxEventAge    = 30 * 24 * time.Hour
	statusAccepted = http.StatusAccepted
)

// Platforms the app reports from.
var platforms = map[string]bool{"android": true, "desktop": true, "web": true, "ios": true}

var (
	versionRe = regexp.MustCompile(`^[0-9A-Za-z][0-9A-Za-z.+-]{0,31}$`)
	osRe      = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9 ._-]{0,31}$`)
)

var (
	crashesIn = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "libraryz_client_crashes_total",
		Help: "Crash reports accepted from the app, by platform and kind (fatal, nonfatal).",
	}, []string{"platform", "kind"})
	eventsIn = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "libraryz_client_events_total",
		Help: "Usage events accepted from the app, by catalog event name.",
	}, []string{"event"})
	refused = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "libraryz_telemetry_rejected_total",
		Help: "Telemetry items refused, by kind (crash, event) and reason.",
	}, []string{"kind", "reason"})
)

func init() { obs.Registry.MustRegister(crashesIn, eventsIn, refused) }

// Handler serves POST /telemetry/crashes and POST /telemetry/events. Both
// are anonymous: a bearer token, if one comes along, is ignored.
type Handler struct {
	catalog *Catalog
	sink    Sink
	now     func() time.Time
}

func NewHandler(c *Catalog, sink Sink) *Handler {
	return &Handler{catalog: c, sink: sink, now: time.Now}
}

type crashBatch struct {
	Client  Client    `json:"client"`
	Crashes []crashIn `json:"crashes"`
}

type crashIn struct {
	Kind       string    `json:"kind"`
	Exception  string    `json:"exception"`
	Stack      []string  `json:"stack"`
	OccurredAt time.Time `json:"occurred_at"`
}

type eventBatch struct {
	Client    Client    `json:"client"`
	InstallID string    `json:"install_id"`
	SessionID string    `json:"session_id"`
	Events    []eventIn `json:"events"`
}

type eventIn struct {
	Name       string                     `json:"name"`
	Props      map[string]json.RawMessage `json:"props"`
	OccurredAt time.Time                  `json:"occurred_at"`
}

// Result is the 202 body: how many items were kept and refused.
type Result struct {
	Accepted int `json:"accepted"`
	Rejected int `json:"rejected"`
}

func (h *Handler) Crashes(w http.ResponseWriter, r *http.Request) {
	var b crashBatch
	if !decode(w, r, MaxCrashBody, &b) {
		return
	}
	if msg := checkClient(b.Client); msg != "" {
		refused.WithLabelValues("crash", "client").Inc()
		http.Error(w, msg, http.StatusBadRequest)
		return
	}
	if len(b.Crashes) == 0 || len(b.Crashes) > MaxCrashBatch {
		http.Error(w, "crashes: send 1 to 10", http.StatusBadRequest)
		return
	}
	now := h.now().UTC()
	var out []Crash
	res := Result{}
	for _, c := range b.Crashes {
		if c.Kind != "fatal" && c.Kind != "nonfatal" {
			refused.WithLabelValues("crash", "kind").Inc()
			res.Rejected++
			continue
		}
		exc, stack := cleanException(c.Exception), cleanStack(c.Stack)
		out = append(out, Crash{
			Client:      b.Client,
			Kind:        c.Kind,
			Exception:   exc,
			Stack:       stack,
			Fingerprint: fingerprint(b.Client.Platform, exc, stack),
			OccurredAt:  when(c.OccurredAt, now),
			ReceivedAt:  now,
		})
	}
	if len(out) > 0 {
		if err := h.sink.WriteCrashes(r.Context(), out); err != nil {
			httpx.ServerError(w, r, "telemetry crashes", err)
			return
		}
		for _, c := range out {
			crashesIn.WithLabelValues(c.Platform, c.Kind).Inc()
		}
	}
	res.Accepted = len(out)
	writeResult(w, res)
}

func (h *Handler) Events(w http.ResponseWriter, r *http.Request) {
	var b eventBatch
	if !decode(w, r, MaxEventBody, &b) {
		return
	}
	if msg := checkClient(b.Client); msg != "" {
		refused.WithLabelValues("event", "client").Inc()
		http.Error(w, msg, http.StatusBadRequest)
		return
	}
	if !isUUID(b.InstallID) || !isUUID(b.SessionID) {
		http.Error(w, "install_id and session_id must be UUIDs", http.StatusBadRequest)
		return
	}
	if len(b.Events) == 0 || len(b.Events) > MaxEventBatch {
		http.Error(w, "events: send 1 to 100", http.StatusBadRequest)
		return
	}
	now := h.now().UTC()
	var out []Event
	res := Result{}
	for _, e := range b.Events {
		props, err := h.catalog.Check(e.Name, e.Props)
		if err != nil {
			reason := "props"
			if errors.Is(err, errUnknownEvent) {
				reason = "unknown_event"
			}
			refused.WithLabelValues("event", reason).Inc()
			res.Rejected++
			continue
		}
		out = append(out, Event{
			Client:     b.Client,
			Name:       e.Name,
			Props:      props,
			InstallID:  b.InstallID,
			SessionID:  b.SessionID,
			OccurredAt: when(e.OccurredAt, now),
			ReceivedAt: now,
		})
	}
	if len(out) > 0 {
		if err := h.sink.WriteEvents(r.Context(), out); err != nil {
			httpx.ServerError(w, r, "telemetry events", err)
			return
		}
		for _, e := range out {
			eventsIn.WithLabelValues(e.Name).Inc()
		}
	}
	res.Accepted = len(out)
	writeResult(w, res)
}

// decode reads a JSON body of at most limit bytes with no unknown fields,
// so nothing the server doesn't expect (a message, a user id) gets in.
func decode(w http.ResponseWriter, r *http.Request, limit int64, v any) bool {
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, limit))
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			http.Error(w, "request body too large", http.StatusRequestEntityTooLarge)
		} else {
			http.Error(w, "invalid JSON body", http.StatusBadRequest)
		}
		return false
	}
	return true
}

func checkClient(c Client) string {
	switch {
	case !platforms[c.Platform]:
		return "client.platform: want android, desktop, web or ios"
	case !versionRe.MatchString(c.AppVersion):
		return "client.app_version: invalid"
	case c.OS != "" && !osRe.MatchString(c.OS):
		return "client.os: invalid"
	}
	return ""
}

func isUUID(s string) bool {
	_, err := uuid.Parse(s)
	return err == nil && len(s) == 36
}

// when is t if it's plausible (not in the future, not older than a month),
// else now.
func when(t, now time.Time) time.Time {
	if t.IsZero() || t.After(now.Add(maxClockSkew)) || t.Before(now.Add(-maxEventAge)) {
		return now
	}
	return t.UTC()
}

func writeResult(w http.ResponseWriter, res Result) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(statusAccepted)
	_ = json.NewEncoder(w).Encode(res)
}
