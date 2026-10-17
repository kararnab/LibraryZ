package telemetry

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"time"

	"github.com/google/uuid"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

// Client describes the sending app: platform, version and OS family.
type Client struct {
	Platform   string `json:"platform"`
	AppVersion string `json:"app_version"`
	OS         string `json:"os"`
}

// Crash is a checked, scrubbed crash report.
type Crash struct {
	Client
	Kind        string // fatal or nonfatal
	Exception   string
	Stack       []string
	Fingerprint string
	OccurredAt  time.Time
	ReceivedAt  time.Time
}

// Event is a usage event whose props matched the catalog.
type Event struct {
	Client
	Name       string
	Props      map[string]any
	InstallID  string
	SessionID  string
	OccurredAt time.Time
	ReceivedAt time.Time
}

// Sink is where accepted telemetry goes. StoreSink keeps it in our
// database; an adapter for an error tracker (Sentry) or a product analytics
// service implements the same interface and goes beside it in a Fanout.
// Sinks only ever see checked and scrubbed data.
type Sink interface {
	WriteCrashes(ctx context.Context, crashes []Crash) error
	WriteEvents(ctx context.Context, events []Event) error
}

// Fanout writes to every sink and joins their errors.
type Fanout []Sink

func (f Fanout) WriteCrashes(ctx context.Context, crashes []Crash) error {
	var errs []error
	for _, s := range f {
		errs = append(errs, s.WriteCrashes(ctx, crashes))
	}
	return errors.Join(errs...)
}

func (f Fanout) WriteEvents(ctx context.Context, events []Event) error {
	var errs []error
	for _, s := range f {
		errs = append(errs, s.WriteEvents(ctx, events))
	}
	return errors.Join(errs...)
}

// StoreSink keeps telemetry in the crash_groups, crash_reports and
// telemetry_events tables.
type StoreSink struct{ DB *gorm.DB }

func (s StoreSink) WriteCrashes(ctx context.Context, crashes []Crash) error {
	return s.DB.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		for _, c := range crashes {
			group := CrashGroup{
				Fingerprint:    c.Fingerprint,
				Platform:       c.Platform,
				Exception:      c.Exception,
				TopFrame:       topFrame(c.Stack),
				Stack:          strings.Join(c.Stack, "\n"),
				Count:          1,
				FirstSeen:      c.ReceivedAt,
				LastSeen:       c.ReceivedAt,
				LastAppVersion: c.AppVersion,
			}
			err := tx.Clauses(clause.OnConflict{
				Columns: []clause.Column{{Name: "fingerprint"}},
				DoUpdates: clause.Assignments(map[string]any{
					"count":            gorm.Expr("crash_groups.count + 1"),
					"last_seen":        c.ReceivedAt,
					"last_app_version": c.AppVersion,
					"stack":            group.Stack,
					"top_frame":        group.TopFrame,
				}),
			}).Create(&group).Error
			if err != nil {
				return err
			}
			report := CrashReport{
				ID:          uuid.New(),
				Fingerprint: c.Fingerprint,
				Kind:        c.Kind,
				Platform:    c.Platform,
				AppVersion:  c.AppVersion,
				OS:          c.OS,
				OccurredAt:  c.OccurredAt,
				ReceivedAt:  c.ReceivedAt,
			}
			if err := tx.Create(&report).Error; err != nil {
				return err
			}
		}
		return nil
	})
}

func (s StoreSink) WriteEvents(ctx context.Context, events []Event) error {
	if len(events) == 0 {
		return nil
	}
	rows := make([]EventRecord, 0, len(events))
	for _, e := range events {
		props, err := json.Marshal(e.Props)
		if err != nil {
			return err
		}
		rows = append(rows, EventRecord{
			ID:         uuid.New(),
			Name:       e.Name,
			Props:      props,
			Platform:   e.Platform,
			AppVersion: e.AppVersion,
			OS:         e.OS,
			InstallID:  e.InstallID,
			SessionID:  e.SessionID,
			OccurredAt: e.OccurredAt,
			ReceivedAt: e.ReceivedAt,
		})
	}
	return s.DB.WithContext(ctx).Create(&rows).Error
}

// Purge deletes what arrived before cutoff: reports, events, and crash
// groups not seen since. Idempotent, so every replica may run it.
func Purge(ctx context.Context, db *gorm.DB, cutoff time.Time) (int64, error) {
	var total int64
	for _, step := range []struct {
		model any
		where string
	}{
		{&CrashReport{}, "received_at < ?"},
		{&EventRecord{}, "received_at < ?"},
		{&CrashGroup{}, "last_seen < ?"},
	} {
		res := db.WithContext(ctx).Where(step.where, cutoff).Delete(step.model)
		if res.Error != nil {
			return total, res.Error
		}
		total += res.RowsAffected
	}
	return total, nil
}
