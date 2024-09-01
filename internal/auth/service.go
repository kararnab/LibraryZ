package auth

import (
	"errors"
	"github.com/kararnab/libraryZ/pkg/utils"
	"gorm.io/gorm"
)

type Service struct {
	db *gorm.DB
}

func NewService(db *gorm.DB) *Service {
	return &Service{db: db}
}

func (s *Service) CreateUser(user User) error {
	hashedPassword, err := utils.HashPassword(user.Password)
	if err != nil {
		return err
	}

	u := User{Email: user.Email, Password: hashedPassword, Name: user.Name}
	return s.db.Create(&u).Error
}

func (s *Service) Authenticate(email, password string) (string, error) {
	var user User
	if err := s.db.Where("email = ?", email).First(&user).Error; err != nil {
		return "", err
	}

	if !utils.CheckPasswordHash(password, user.Password) {
		return "", errors.New("invalid credentials")
	}

	token, err := utils.GenerateJWT(user.ID)
	if err != nil {
		return "", err
	}

	return token, nil
}
