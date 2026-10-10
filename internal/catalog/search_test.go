package catalog

import "testing"

func TestEscapeLike(t *testing.T) {
	for in, want := range map[string]string{
		"plain":      "plain",
		"100%":       `100\%`,
		"snake_case": `snake\_case`,
		`back\slash`: `back\\slash`,
		`%_\`:        `\%\_\\`,
	} {
		if got := escapeLike(in); got != want {
			t.Errorf("escapeLike(%q) = %q, want %q", in, got, want)
		}
	}
}
