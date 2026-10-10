package auth

import (
	"bytes"
	"context"
	"embed"
	htmltemplate "html/template"
	"log/slog"
	"net/url"
	texttemplate "text/template"
	"time"

	"github.com/kararnab/onemailer"
	"gorm.io/gorm"
)

// Email kinds, as logged by onemailer (password_reset_sent, …) and stored
// in account_emails.
const (
	KindPasswordReset     = "password_reset"
	KindEmailVerification = "email_verification"
)

// Per-account caps on recovery emails, per kind: no more than one every
// EmailCooldown and EmailDailyCap per 24 hours. They protect a person's
// inbox, which gateway limits can't (they see IPs, not accounts).
const (
	EmailCooldown = 2 * time.Minute
	EmailDailyCap = 5
	emailWindow   = 24 * time.Hour
)

//go:embed templates/*.tmpl
var templateFiles embed.FS

var (
	resetText  = texttemplate.Must(texttemplate.ParseFS(templateFiles, "templates/password_reset.txt.tmpl"))
	resetHTML  = htmltemplate.Must(htmltemplate.ParseFS(templateFiles, "templates/password_reset.html.tmpl"))
	verifyText = texttemplate.Must(texttemplate.ParseFS(templateFiles, "templates/verify_email.txt.tmpl"))
	verifyHTML = htmltemplate.Must(htmltemplate.ParseFS(templateFiles, "templates/verify_email.html.tmpl"))
)

// Outbox accepts messages for delivery off the request path:
// *onemailer.Queue in production, a recorder in tests.
type Outbox interface {
	Enqueue(m onemailer.Message) bool
}

// Mail configures the recovery emails.
type Mail struct {
	// Outbox delivers messages; nil means mail is off (nothing is sent,
	// and requests are answered as usual).
	Outbox Outbox
	// PublicURL is the web app's origin; links are
	// {PublicURL}/reset-password?token=… and /verify-email?token=….
	PublicURL string
	// CanReply adds "Questions? Reply to this email." (a Reply-To is set).
	CanReply bool
}

// emailData is what the templates see. Link and Code carry the token: they
// go into the message only, never into a log.
type emailData struct {
	DisplayName, Email, Link, Code, Lifetime string
	CanReply                                 bool
}

func renderEmail(kind string, d emailData) (onemailer.Message, error) {
	textT, htmlT, subject := resetText, resetHTML, "Reset your LibraryZ password"
	if kind == KindEmailVerification {
		textT, htmlT, subject = verifyText, verifyHTML, "Confirm your email for LibraryZ"
	}
	var text, html bytes.Buffer
	if err := textT.Execute(&text, d); err != nil {
		return onemailer.Message{}, err
	}
	if err := htmlT.Execute(&html, d); err != nil {
		return onemailer.Message{}, err
	}
	return onemailer.Message{Kind: kind, To: d.Email, Subject: subject, Text: text.String(), HTML: html.String()}, nil
}

// reserveEmail checks the account's cap for kind and, when there is room,
// records the send up front. It returns reason "cooldown" or "daily_cap"
// when capped, and a release func that forgets the record (call it when
// the message could not be queued). On Postgres the user's row is locked
// so concurrent requests are counted one after the other.
func (h *Handler) reserveEmail(ctx context.Context, userID uint, kind string) (reason string, release func(), err error) {
	now := h.auth.now()
	var id uint
	err = h.auth.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if tx.Dialector.Name() == "postgres" {
			if err := tx.Exec("SELECT 1 FROM users WHERE id = ? FOR UPDATE", userID).Error; err != nil {
				return err
			}
		}
		count := func(since time.Time) (int64, error) {
			var n int64
			err := tx.Model(&AccountEmail{}).
				Where("user_id = ? AND kind = ? AND sent_at > ?", userID, kind, since).Count(&n).Error
			return n, err
		}
		recent, err := count(now.Add(-EmailCooldown))
		if err != nil {
			return err
		}
		today, err := count(now.Add(-emailWindow))
		if err != nil {
			return err
		}
		switch {
		case recent > 0:
			reason = "cooldown"
			return nil
		case today >= EmailDailyCap:
			reason = "daily_cap"
			return nil
		}
		row := AccountEmail{UserID: userID, Kind: kind, SentAt: now}
		if err := tx.Create(&row).Error; err != nil {
			return err
		}
		id = row.ID
		return nil
	})
	release = func() {
		if id != 0 {
			_ = h.auth.db.Delete(&AccountEmail{}, id).Error
		}
	}
	return reason, release, err
}

// send renders and queues one recovery email. Nothing about the token is
// logged; a message that can't be queued gives its cap slot back.
func (h *Handler) send(kind string, u *User, token string, ttl time.Duration, release func()) {
	path := "/reset-password"
	if kind == KindEmailVerification {
		path = "/verify-email"
	}
	link := ""
	if h.mail.PublicURL != "" {
		link = h.mail.PublicURL + path + "?token=" + url.QueryEscape(token)
	}
	name := u.Name
	if name == "" {
		name = "there"
	}
	m, err := renderEmail(kind, emailData{
		DisplayName: name, Email: u.Email, Link: link, Code: token,
		Lifetime: onemailer.Lifetime(ttl), CanReply: h.mail.CanReply,
	})
	if err != nil {
		slog.Error("mail not rendered", "event", kind+"_not_sent", "reason", "template", "user_id", u.ID, "error", err.Error())
		release()
		return
	}
	m.Attrs = []slog.Attr{slog.Uint64("user_id", uint64(u.ID))}
	if h.mail.Outbox == nil {
		slog.Info("mail not sent", "event", kind+"_not_sent", "reason", "mail_off", "user_id", u.ID)
		release()
		return
	}
	if !h.mail.Outbox.Enqueue(m) {
		release() // onemailer logged why (queue_full / shutdown)
	}
}

// PurgeAccountEmails deletes send records older than the daily window.
func PurgeAccountEmails(ctx context.Context, db *gorm.DB, now time.Time) (int, error) {
	res := db.WithContext(ctx).Where("sent_at <= ?", now.Add(-emailWindow)).Delete(&AccountEmail{})
	return int(res.RowsAffected), res.Error
}
