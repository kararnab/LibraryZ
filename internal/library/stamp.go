package library

import (
	"errors"
	"fmt"
	"regexp"
	"strconv"
	"time"
)

// A Stamp orders writes to one field group: a hybrid logical clock,
// "<unix ms, 13 digits>.<counter, 4 digits>.<device id>". Fixed widths make
// plain string comparison the clock order; the device id breaks ties. Apps
// stamp a change when it's made (offline included), so the newest change
// wins wherever and whenever it's synced. A write without a stamp gets the
// server's own.

// ErrInvalidStamp is a malformed stamp in a request.
var ErrInvalidStamp = errors.New("invalid stamp")

var stampRe = regexp.MustCompile(`^(\d{13})\.\d{4}\.[A-Za-z0-9_-]{1,32}$`)

// maxSkew is how far ahead of the server's clock a stamp may be. A device
// whose clock runs further ahead would otherwise win every merge until the
// real time catches up; its stamps are pulled back to now instead.
const maxSkew = 5 * time.Minute

func serverStamp(now time.Time) string {
	return fmt.Sprintf("%013d.0000.server", now.UnixMilli())
}

// checkStamp validates s; empty means "now". A stamp too far in the future
// is replaced by now.
func checkStamp(s string, now time.Time) (string, error) {
	if s == "" {
		return serverStamp(now), nil
	}
	m := stampRe.FindStringSubmatch(s)
	if m == nil {
		return "", ErrInvalidStamp
	}
	ms, _ := strconv.ParseInt(m[1], 10, 64)
	if ms > now.Add(maxSkew).UnixMilli() {
		return serverStamp(now), nil
	}
	return s, nil
}
