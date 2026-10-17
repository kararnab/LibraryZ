package telemetry

import (
	"bytes"
	_ "embed"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"slices"
)

//go:embed catalog.json
var catalogJSON []byte

// Catalog is the list of usage events the app may send (catalog.json).
// Props are typed and closed: enums, bools and bounded ints only, so no
// event can carry free text.
type Catalog struct {
	Events []EventSpec `json:"events"`
	byName map[string]*EventSpec
}

type EventSpec struct {
	Name        string     `json:"name"`
	Description string     `json:"description"`
	Props       []PropSpec `json:"props"`
}

type PropSpec struct {
	Name   string   `json:"name"`
	Type   string   `json:"type"` // "enum", "bool" or "int"
	Values []string `json:"values,omitempty"`
	Min    *int64   `json:"min,omitempty"`
	Max    *int64   `json:"max,omitempty"`
}

var identRe = regexp.MustCompile(`^[a-z][a-z0-9_]{0,47}$`)

// DefaultCatalog is the embedded catalog.json.
func DefaultCatalog() (*Catalog, error) { return ParseCatalog(catalogJSON) }

// ParseCatalog reads and checks a catalog: identifiers are snake_case,
// every enum has values, every int has both bounds.
func ParseCatalog(data []byte) (*Catalog, error) {
	var c Catalog
	dec := json.NewDecoder(bytes.NewReader(data))
	if err := dec.Decode(&c); err != nil {
		return nil, fmt.Errorf("telemetry catalog: %w", err)
	}
	c.byName = make(map[string]*EventSpec, len(c.Events))
	for i := range c.Events {
		e := &c.Events[i]
		if !identRe.MatchString(e.Name) {
			return nil, fmt.Errorf("telemetry catalog: event name %q is not snake_case", e.Name)
		}
		if _, dup := c.byName[e.Name]; dup {
			return nil, fmt.Errorf("telemetry catalog: event %q listed twice", e.Name)
		}
		if len(e.Props) > MaxProps {
			return nil, fmt.Errorf("telemetry catalog: %s: more than %d props", e.Name, MaxProps)
		}
		seen := map[string]bool{}
		for _, p := range e.Props {
			if !identRe.MatchString(p.Name) || seen[p.Name] {
				return nil, fmt.Errorf("telemetry catalog: %s: bad or repeated prop %q", e.Name, p.Name)
			}
			seen[p.Name] = true
			switch p.Type {
			case "enum":
				if len(p.Values) == 0 {
					return nil, fmt.Errorf("telemetry catalog: %s.%s: enum without values", e.Name, p.Name)
				}
				for _, v := range p.Values {
					if !identRe.MatchString(v) {
						return nil, fmt.Errorf("telemetry catalog: %s.%s: value %q is not snake_case", e.Name, p.Name, v)
					}
				}
			case "bool":
			case "int":
				if p.Min == nil || p.Max == nil || *p.Min > *p.Max {
					return nil, fmt.Errorf("telemetry catalog: %s.%s: int needs min <= max", e.Name, p.Name)
				}
			default:
				return nil, fmt.Errorf("telemetry catalog: %s.%s: unknown type %q", e.Name, p.Name, p.Type)
			}
		}
		c.byName[e.Name] = e
	}
	return &c, nil
}

// MaxProps bounds an event's props.
const MaxProps = 16

var (
	errUnknownEvent = errors.New("unknown event")
	errBadProps     = errors.New("props don't match the catalog")
)

// Check returns props as the catalog types them, or an error when the
// event isn't listed or its props aren't exactly the listed ones with
// allowed values.
func (c *Catalog) Check(name string, props map[string]json.RawMessage) (map[string]any, error) {
	spec, ok := c.byName[name]
	if !ok {
		return nil, errUnknownEvent
	}
	if len(props) != len(spec.Props) {
		return nil, errBadProps
	}
	out := make(map[string]any, len(spec.Props))
	for _, p := range spec.Props {
		raw, ok := props[p.Name]
		// null would unmarshal as the zero value; it's not a value.
		if !ok || string(bytes.TrimSpace(raw)) == "null" {
			return nil, errBadProps
		}
		switch p.Type {
		case "enum":
			var s string
			if json.Unmarshal(raw, &s) != nil || !slices.Contains(p.Values, s) {
				return nil, errBadProps
			}
			out[p.Name] = s
		case "bool":
			var b bool
			if json.Unmarshal(raw, &b) != nil {
				return nil, errBadProps
			}
			out[p.Name] = b
		case "int":
			var n int64
			if json.Unmarshal(raw, &n) != nil || n < *p.Min || n > *p.Max {
				return nil, errBadProps
			}
			out[p.Name] = n
		}
	}
	return out, nil
}
