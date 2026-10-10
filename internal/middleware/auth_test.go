package middleware

import (
	"context"
	"testing"

	"github.com/glebarez/sqlite"
	"github.com/kararnab/iam/v2"
	"github.com/kararnab/iam/v2/httpauth"
	"gorm.io/gorm"
)

func TestParseSubjectID(t *testing.T) {
	for in, want := range map[string]uint{"1": 1, "42": 42, "18446744073709551615": ^uint(0)} {
		if got, ok := ParseSubjectID(in); !ok || got != want {
			t.Errorf("ParseSubjectID(%q) = %d, %v", in, got, ok)
		}
		if SubjectID(want) != in {
			t.Errorf("SubjectID(%d) = %q", want, SubjectID(want))
		}
	}
	for _, in := range []string{"", "0", "01", "-1", "+1", "1.5", " 1", "abc", "18446744073709551616", "J7NSXYZ"} {
		if got, ok := ParseSubjectID(in); ok {
			t.Errorf("ParseSubjectID(%q) = %d, want rejected", in, got)
		}
	}
}

func TestUserID(t *testing.T) {
	if _, ok := UserID(context.Background()); ok {
		t.Fatal("anonymous context has a user id")
	}
	ctx := httpauth.WithSubject(context.Background(), &iam.Subject{ID: "7"}, nil)
	if id, ok := UserID(ctx); !ok || id != 7 {
		t.Fatalf("UserID = %d, %v", id, ok)
	}
	// A subject id that isn't ours (non-numeric) is not a user.
	ctx = httpauth.WithSubject(context.Background(), &iam.Subject{ID: "not-a-number"}, nil)
	if _, ok := UserID(ctx); ok {
		t.Fatal("non-numeric subject accepted")
	}
}

func TestIsModerator(t *testing.T) {
	db, err := gorm.Open(sqlite.Open("file::memory:"), &gorm.Config{})
	if err != nil {
		t.Fatal(err)
	}
	sqlDB, _ := db.DB()
	sqlDB.SetMaxOpenConns(1)
	for _, s := range []string{
		`CREATE TABLE user_roles (user_id INTEGER, role TEXT, PRIMARY KEY (user_id, role))`,
		`INSERT INTO user_roles (user_id, role) VALUES (1, 'moderator'), (2, 'reader')`,
	} {
		if err := db.Exec(s).Error; err != nil {
			t.Fatal(err)
		}
	}
	for id, want := range map[uint]bool{1: true, 2: false, 99: false} {
		got, err := IsModerator(context.Background(), db, id)
		if err != nil || got != want {
			t.Errorf("IsModerator(%d) = %v, %v; want %v", id, got, err, want)
		}
	}
}
