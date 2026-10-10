package auth

import (
	"context"

	"github.com/kararnab/iam/v2"
	"github.com/kararnab/iam/v2/password"
	"github.com/kararnab/iam/v2/provider"
	"github.com/kararnab/libraryZ/internal/middleware"
	"gorm.io/gorm"
)

// Users implements iam.UserStore and password.CredentialStore over the
// users, user_roles, identities and password_credentials tables.
//
// LibraryZ keeps numeric user ids everywhere else (contributions,
// user_books, rec_*, editions.uploaded_by), so ids cross the iam boundary
// as decimal strings.
type Users struct{ db *gorm.DB }

var (
	_ iam.UserStore            = (*Users)(nil)
	_ password.CredentialStore = (*Users)(nil)
)

// NewUsers returns the user store.
func NewUsers(db *gorm.DB) *Users { return &Users{db: db} }

// Subject ids cross the iam boundary as decimal strings; see
// middleware.SubjectID / middleware.ParseSubjectID.
var (
	SubjectID      = middleware.SubjectID
	ParseSubjectID = middleware.ParseSubjectID
)

// LoadSubject implements iam.SubjectLoader. Roles come back sorted by name.
func (u *Users) LoadSubject(ctx context.Context, subjectID string) (*iam.Subject, error) {
	id, ok := ParseSubjectID(subjectID)
	if !ok {
		return nil, iam.ErrNotFound
	}
	var user User
	if found, err := takeOne(u.db.WithContext(ctx).Select("id", "disabled").Where("id = ?", id), &user); err != nil {
		return nil, err
	} else if !found {
		return nil, iam.ErrNotFound
	}
	roles, err := u.roles(ctx, id)
	if err != nil {
		return nil, err
	}
	return &iam.Subject{ID: subjectID, Roles: roles, Disabled: user.Disabled}, nil
}

func (u *Users) roles(ctx context.Context, id uint) ([]string, error) {
	var roles []string
	err := u.db.WithContext(ctx).Model(&UserRole{}).Where("user_id = ?", id).
		Order("role").Pluck("role", &roles).Error
	return roles, err
}

// ResolveIdentity implements iam.IdentityStore.
func (u *Users) ResolveIdentity(ctx context.Context, prov, providerID string) (string, error) {
	var ident Identity
	found, err := takeOne(u.db.WithContext(ctx).Where("provider = ? AND provider_id = ?", prov, providerID), &ident)
	if err != nil {
		return "", err
	}
	if !found {
		return "", iam.ErrNotFound
	}
	return SubjectID(ident.UserID), nil
}

// LinkIdentity implements iam.IdentityStore.
func (u *Users) LinkIdentity(ctx context.Context, subjectID string, id provider.Identity) error {
	uid, ok := ParseSubjectID(subjectID)
	if !ok {
		return iam.ErrNotFound
	}
	db := u.db.WithContext(ctx)
	if found, err := takeOne(db.Select("id").Where("id = ?", uid), &User{}); err != nil {
		return err
	} else if !found {
		return iam.ErrNotFound
	}
	err := db.Create(&Identity{Provider: id.Provider, ProviderID: id.ProviderID, UserID: uid}).Error
	if err != nil {
		return conflictIfExists(db.Model(&Identity{}).
			Where("provider = ? AND provider_id = ?", id.Provider, id.ProviderID), err)
	}
	return nil
}

// UnlinkIdentity implements iam.IdentityStore.
func (u *Users) UnlinkIdentity(ctx context.Context, subjectID, prov, providerID string) error {
	uid, ok := ParseSubjectID(subjectID)
	if !ok {
		return nil
	}
	return u.db.WithContext(ctx).
		Where("user_id = ? AND provider = ? AND provider_id = ?", uid, prov, providerID).
		Delete(&Identity{}).Error
}

// CreateSubject implements iam.IdentityStore: a users row plus the granted
// roles, in one transaction. It doesn't link the identity (iam does).
func (u *Users) CreateSubject(ctx context.Context, id provider.Identity, grant iam.SignupGrant) (string, error) {
	user := User{Email: id.Email}
	db := u.db.WithContext(ctx)
	err := db.Transaction(func(tx *gorm.DB) error {
		if err := tx.Create(&user).Error; err != nil {
			return err
		}
		for _, role := range grant.Roles {
			if err := tx.Create(&UserRole{UserID: user.ID, Role: role}).Error; err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		// Checked after the rollback: an aborted Postgres transaction
		// can't run the lookup.
		if id.Email != "" {
			return "", conflictIfExists(db.Model(&User{}).Where("email = ?", id.Email), err)
		}
		return "", err
	}
	return SubjectID(user.ID), nil
}

// GetCredential implements password.CredentialStore.
func (u *Users) GetCredential(ctx context.Context, login string) (string, error) {
	var c PasswordCredential
	found, err := takeOne(u.db.WithContext(ctx).Where("login = ?", login), &c)
	if err != nil {
		return "", err
	}
	if !found {
		return "", iam.ErrNotFound
	}
	return c.Hash, nil
}

// CreateCredential implements password.CredentialStore.
func (u *Users) CreateCredential(ctx context.Context, login, encodedHash string) error {
	db := u.db.WithContext(ctx)
	if err := db.Create(&PasswordCredential{Login: login, Hash: encodedHash}).Error; err != nil {
		return conflictIfExists(db.Model(&PasswordCredential{}).Where("login = ?", login), err)
	}
	return nil
}

// UpdateCredential implements password.CredentialStore.
func (u *Users) UpdateCredential(ctx context.Context, login, encodedHash string) error {
	res := u.db.WithContext(ctx).Model(&PasswordCredential{}).Where("login = ?", login).Update("hash", encodedHash)
	if res.Error != nil {
		return res.Error
	}
	if res.RowsAffected == 0 {
		return iam.ErrNotFound
	}
	return nil
}

// DeleteCredential implements password.CredentialStore.
func (u *Users) DeleteCredential(ctx context.Context, login string) error {
	return u.db.WithContext(ctx).Where("login = ?", login).Delete(&PasswordCredential{}).Error
}

// conflictIfExists turns a failed insert into iam.ErrConflict when the row
// it collided with exists. Checking after the fact keeps this independent
// of how each dialect reports unique violations (sqlite tests + Postgres).
func conflictIfExists(existing *gorm.DB, insertErr error) error {
	var n int64
	if err := existing.Count(&n).Error; err == nil && n > 0 {
		return iam.ErrConflict
	}
	return insertErr
}

// takeOne loads at most one row into dest and reports whether there was
// one. Unlike Take/First, a miss isn't an error, so GORM doesn't log it:
// misses here are routine (unknown logins, sign-ups) and the logged SQL
// would carry attacker-supplied values.
func takeOne(q *gorm.DB, dest any) (bool, error) {
	res := q.Limit(1).Find(dest)
	return res.RowsAffected > 0, res.Error
}
