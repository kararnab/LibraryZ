package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"os"
	"time"

	"github.com/kararnab/libraryZ/internal/auth"
	"github.com/kararnab/onemailer"
)

// mailEnvPrefix names LibraryZ's mail settings: LIBRARYZ_MAIL_PROVIDER,
// LIBRARYZ_SMTP_HOST, … (see onemailer.LoadConfig and the README).
const mailEnvPrefix = "LIBRARYZ_"

// newMail builds the recovery-email settings. With mail off it returns the
// zero auth.Mail (requests still answered; nothing sent) and no queue.
func newMail(cfg onemailer.Config) (auth.Mail, *onemailer.Queue, error) {
	if !cfg.Enabled() {
		return auth.Mail{PublicURL: cfg.PublicURL}, nil, nil
	}
	sender, err := onemailer.NewSMTPSender(cfg)
	if err != nil {
		return auth.Mail{}, nil, err
	}
	q := onemailer.NewQueue(sender, onemailer.QueueConfig{})
	return auth.Mail{Outbox: q, PublicURL: cfg.PublicURL, CanReply: cfg.ReplyTo != nil}, q, nil
}

const mailUsage = `usage: libraryz mail send-test -to ADDRESS

  send-test -to ADDRESS   send one test message with the LIBRARYZ_MAIL_* and
                          LIBRARYZ_SMTP_* settings and print the outcome

Exit 0 when the SMTP server accepted it, 1 when sending failed, 2 for usage
or configuration errors.
`

func mailCommand(args []string) int {
	host, _ := os.Hostname()
	return runMail(args, os.Getenv, os.Stdout, os.Stderr, host, time.Now)
}

func runMail(args []string, getenv func(string) string, stdout, stderr io.Writer, host string, now func() time.Time) int {
	if len(args) == 0 || args[0] != "send-test" {
		fmt.Fprint(stderr, mailUsage)
		return 2
	}
	fs := flag.NewFlagSet("send-test", flag.ContinueOnError)
	fs.SetOutput(stderr)
	to := fs.String("to", "", "the recipient")
	if err := fs.Parse(args[1:]); err != nil {
		return 2
	}
	if *to == "" || fs.NArg() != 0 {
		fmt.Fprint(stderr, mailUsage)
		return 2
	}
	cfg, err := onemailer.LoadConfig(getenv, mailEnvPrefix)
	if err != nil {
		fmt.Fprintf(stderr, "configuration: %v\n", err)
		return 2
	}
	sender, err := onemailer.NewSMTPSender(cfg)
	if err != nil {
		fmt.Fprintln(stderr, "configuration: LIBRARYZ_MAIL_PROVIDER is none; set it to smtp")
		return 2
	}
	m := onemailer.Message{
		Kind:    "mail_test",
		To:      *to,
		Subject: "LibraryZ mail test",
		Text: fmt.Sprintf("This is a test message from LibraryZ, sent by %s at %s.\n\n"+
			"If you can read it, LibraryZ can send mail with this configuration:\n%s\n", host, now().UTC().Format(time.RFC3339), cfg),
	}
	fmt.Fprintf(stdout, "sending to %s via %s\n", *to, cfg)
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	if err := sender.Send(ctx, m); err != nil {
		if code := onemailer.SMTPCode(err); code != 0 {
			fmt.Fprintf(stderr, "not sent: SMTP %d: %v\n", code, err)
		} else {
			fmt.Fprintf(stderr, "not sent: %v\n", err)
		}
		return 1
	}
	fmt.Fprintln(stdout, "sent: the SMTP server accepted the message")
	return 0
}
