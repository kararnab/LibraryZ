package utils

import "fmt"

// Error codes
const (
	ErrCodeDBInitFailed         = 1001
	ErrCodeDBSQLRetrievalFailed = 1002
	// Add more error codes as needed...
)

// ErrorDescriptions maps error codes to their descriptions
var ErrorDescriptions = map[int]string{
	ErrCodeDBInitFailed:         "Database initialization failed. Possible Causes: Incorrect connection string, network issues, or authentication failure.",
	ErrCodeDBSQLRetrievalFailed: "Failed to retrieve the underlying SQL DB object from the GORM DB instance. Possible Causes: Internal Gorm issues, improper initialization, driver problems",
	// Add more descriptions as needed...
}

// AppError is a custom error type that includes an error code and a message
type AppError struct {
	Code        int
	Description string
	Message     string
	Err         error
}

// Error method returns the error message of AppError
func (e *AppError) Error() string {
	return fmt.Sprintf("[%d] %s: %v", e.Code, e.Message, e.Err)
}

// WrappedError creates a new AppError
func WrappedError(code int, message string, err error) *AppError {
	description := ErrorDescriptions[code]
	return &AppError{
		Code:        code,
		Description: description,
		Message:     message,
		Err:         err,
	}
}
