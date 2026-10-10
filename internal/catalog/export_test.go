package catalog

import "time"

// SetNow replaces the service clock (quota windows) in tests.
func (s *Service) SetNow(now func() time.Time) { s.now = now }
