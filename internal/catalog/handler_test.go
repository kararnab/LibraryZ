package catalog

import (
	"strings"
	"testing"

	"github.com/google/uuid"
)

func TestDownloadFilename(t *testing.T) {
	ed := &Edition{ID: uuid.MustParse("3f2a0000-0000-0000-0000-0000000000e1"), Format: "PDF"}
	cases := []struct{ title, authors, want string }{
		{"Moby-Dick", "Herman Melville", "Moby-Dick - Herman Melville.pdf"},
		{"Moby-Dick", "", "Moby-Dick.pdf"},
		{"", "Anon", "3f2a0000-0000-0000-0000-0000000000e1.pdf"},
		{"../../etc/passwd", "", "etc passwd.pdf"},
		{"a/b\\c:d*e?f\"g<h>i|j", "", "a b c d e f g h i j.pdf"},
		{"Line\nbreak\x00\tTitle", "", "Line break Title.pdf"},
		{"...hidden", "", "hidden.pdf"},
		{"Война и мир", "Лев Толстой", "Война и мир - Лев Толстой.pdf"},
	}
	for _, c := range cases {
		if got := downloadFilename(c.title, c.authors, ed); got != c.want {
			t.Errorf("downloadFilename(%q, %q) = %q, want %q", c.title, c.authors, got, c.want)
		}
	}

	long := downloadFilename(strings.Repeat("ж", 500), "", ed)
	if n := len([]rune(strings.TrimSuffix(long, ".pdf"))); n != maxFilenameStem {
		t.Errorf("long title stem = %d runes, want %d", n, maxFilenameStem)
	}
}

func TestContentDisposition(t *testing.T) {
	got := contentDisposition(`Война "и" мир; x=1.pdf`)
	want := `attachment; filename="_____ ___ ___; x=1.pdf"; filename*=UTF-8''` +
		`%D0%92%D0%BE%D0%B9%D0%BD%D0%B0%20%22%D0%B8%22%20%D0%BC%D0%B8%D1%80%3B%20x%3D1.pdf`
	if got != want {
		t.Fatalf("got  %s\nwant %s", got, want)
	}
	if got := contentDisposition("Plain.txt"); got != `attachment; filename="Plain.txt"; filename*=UTF-8''Plain.txt` {
		t.Fatalf("ascii: %s", got)
	}
}
