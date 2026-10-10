package main

import (
	"bytes"
	"strings"
	"testing"
	"time"

	"github.com/kararnab/onemailer/mailtest"
)

func TestMailSendTest(t *testing.T) {
	srv := mailtest.Start(t, mailtest.Options{Mode: mailtest.Plain})
	env := map[string]string{
		"LIBRARYZ_MAIL_PROVIDER": "smtp",
		"LIBRARYZ_PUBLIC_URL":    "http://localhost:8081",
		"LIBRARYZ_MAIL_FROM":     "LibraryZ <accounts@libraryz.localhost>",
		"LIBRARYZ_SMTP_HOST":     "127.0.0.1",
		"LIBRARYZ_SMTP_PORT":     srv.PortString(),
		"LIBRARYZ_SMTP_TLS":      "none",
	}
	var out, errOut bytes.Buffer
	now := func() time.Time { return time.Date(2026, 10, 10, 9, 0, 0, 0, time.UTC) }
	code := runMail([]string{"send-test", "-to", "owner@example.org"}, func(k string) string { return env[k] }, &out, &errOut, "testhost", now)
	if code != 0 {
		t.Fatalf("exit %d: %s", code, errOut.String())
	}
	msgs := srv.Messages()
	if len(msgs) != 1 || !strings.Contains(msgs[0].Data, "LibraryZ mail test") || !strings.Contains(msgs[0].Data, "testhost") {
		t.Fatalf("unexpected messages: %+v", msgs)
	}
}

func TestMailSendTestRefusesBadConfig(t *testing.T) {
	var out, errOut bytes.Buffer
	env := map[string]string{"LIBRARYZ_MAIL_PROVIDER": "smtp"}
	code := runMail([]string{"send-test", "-to", "x@example.org"}, func(k string) string { return env[k] }, &out, &errOut, "h", time.Now)
	if code != 2 || !strings.Contains(errOut.String(), "LIBRARYZ_PUBLIC_URL") {
		t.Fatalf("exit %d, stderr %q", code, errOut.String())
	}
	if code := runMail(nil, func(string) string { return "" }, &out, &errOut, "h", time.Now); code != 2 {
		t.Fatalf("no args: exit %d", code)
	}
}
