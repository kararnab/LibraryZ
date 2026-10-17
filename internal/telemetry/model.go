// Package telemetry takes in the app's crash reports and usage events, the
// two kinds of client data that are sent only with the user's consent
// (asked once in the app, off until they agree). Operational metrics and
// logs, which need no consent, are internal/obs.
//
// What arrives is checked and cleaned before any Sink sees it: usage events
// must match catalog.json exactly, crash reports carry an exception type and
// a scrubbed stack, never a message. Nothing here is linked to an account;
// the server doesn't store the sender's IP. Events carry a random install id
// the app resets whenever the user turns usage sharing off.
package telemetry

import (
	"time"

	"github.com/google/uuid"
	"gorm.io/datatypes"
)

// CrashGroup is one distinct crash: reports with the same fingerprint
// (platform, exception type, top frames without line numbers).
type CrashGroup struct {
	Fingerprint    string    `gorm:"primaryKey;size:64"`
	Platform       string    `gorm:"size:16;not null"`
	Exception      string    `gorm:"size:200;not null"`
	TopFrame       string    `gorm:"size:300"`
	Stack          string    `gorm:"type:text"` // the latest report's, scrubbed, one frame per line
	Count          int64     `gorm:"not null"`
	FirstSeen      time.Time `gorm:"not null"`
	LastSeen       time.Time `gorm:"not null;index"`
	LastAppVersion string    `gorm:"size:32"`
}

func (CrashGroup) TableName() string { return "crash_groups" }

// CrashReport is one occurrence of a CrashGroup.
type CrashReport struct {
	ID          uuid.UUID `gorm:"type:uuid;primaryKey"`
	Fingerprint string    `gorm:"size:64;not null;index"`
	Kind        string    `gorm:"size:16;not null"` // fatal or nonfatal
	Platform    string    `gorm:"size:16;not null"`
	AppVersion  string    `gorm:"size:32;not null"`
	OS          string    `gorm:"size:32"`
	OccurredAt  time.Time `gorm:"not null"`
	ReceivedAt  time.Time `gorm:"not null;index"`
}

func (CrashReport) TableName() string { return "crash_reports" }

// EventRecord is one usage event.
type EventRecord struct {
	ID         uuid.UUID      `gorm:"type:uuid;primaryKey"`
	Name       string         `gorm:"size:48;not null;index:idx_telemetry_events_name_time,priority:1"`
	Props      datatypes.JSON `gorm:"not null"`
	Platform   string         `gorm:"size:16;not null"`
	AppVersion string         `gorm:"size:32;not null"`
	OS         string         `gorm:"size:32"`
	InstallID  string         `gorm:"size:36;index"`
	SessionID  string         `gorm:"size:36"`
	OccurredAt time.Time      `gorm:"not null;index:idx_telemetry_events_name_time,priority:2"`
	ReceivedAt time.Time      `gorm:"not null;index"`
}

func (EventRecord) TableName() string { return "telemetry_events" }
