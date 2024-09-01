package catalog

import "gorm.io/gorm"

type Service struct {
    db *gorm.DB
}

func NewService(db *gorm.DB) *Service {
    return &Service{db: db}
}

func (s *Service) GetAllBooks() ([]Book, error) {
    var books []Book
    if err := s.db.Find(&books).Error; err != nil {
        return nil, err
    }
    return books, nil
}

func (s *Service) AddBook(book Book) error {
    return s.db.Create(&book).Error
}

func (s *Service) DeleteBook(id string) error {
    return s.db.Delete(&Book{}, id).Error
}