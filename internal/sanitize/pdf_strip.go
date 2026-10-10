package sanitize

import (
	"bytes"
	"context"
	"fmt"
	"io"

	"github.com/pdfcpu/pdfcpu/pkg/api"
	"github.com/pdfcpu/pdfcpu/pkg/pdfcpu/model"
	"github.com/pdfcpu/pdfcpu/pkg/pdfcpu/types"
)

// dangerousPDFKeys are dictionary keys that name active or executable
// content. Deleted whenever encountered, in any dict at any depth. The
// names are matched against the dict key (no leading slash) — pdfcpu
// strips the "/" prefix during parsing.
//
// Not included on purpose:
//   - /A and /F: overloaded (Action in annotations, but also Ascent in
//     fonts, FontFlags, FormType, etc). Per-key deletion would corrupt
//     legitimate non-action uses.
//   - /URI: open-URL actions are visible to the user; readers don't
//     auto-fetch. Keeping them preserves working hyperlinks.
//   - /GoTo, /GoToR: in-document/remote-document navigation. Safe.
var dangerousPDFKeys = []string{
	"JavaScript", "JS",
	"OpenAction",
	"AA",
	"Launch",
	"EmbeddedFile", "EmbeddedFiles",
	"RichMedia",
	"XFA",
	"SubmitForm", "ImportData",
	"AlternatePresentations", "Renditions",
}

// sanitizePDF runs sanitizePDFTo in-process, buffering the output. Peak
// memory is roughly the parsed object graph plus the output, and a small
// PDF with compressed object streams can inflate to gigabytes while
// parsing. That's why the server runs this out of process (see isolate.go);
// in-process is only for tests and library callers that haven't called
// EnableIsolation.
func sanitizePDF(rs io.ReadSeeker) (io.Reader, error) {
	var buf bytes.Buffer
	if err := sanitizePDFTo(rs, &buf); err != nil {
		return nil, err
	}
	return bytes.NewReader(buf.Bytes()), nil
}

// sanitizePDFTo parses rs, strips active content, and writes the
// re-serialized PDF to w.
func sanitizePDFTo(rs io.ReadSeeker, w io.Writer) (retErr error) {
	// pdfcpu can panic deep inside its parser/writer on malformed PDFs
	// (saw a nil-pointer in writeRootObject for a header-only PDF with no
	// object graph). A malformed upload should never crash the server, so
	// recover and surface as ErrInvalidContent.
	defer func() {
		if r := recover(); r != nil {
			retErr = fmt.Errorf("%w: pdfcpu panic: %v", ErrInvalidContent, r)
		}
	}()

	conf := model.NewDefaultConfiguration()
	conf.ValidationMode = model.ValidationRelaxed

	if _, err := rs.Seek(0, io.SeekStart); err != nil {
		return fmt.Errorf("seek: %w", err)
	}
	// Background context: this runs in the isolated child process (see
	// isolate.go), which the parent bounds with a timeout and kills on
	// cancellation, so there's nothing to propagate here.
	bg := context.Background()
	pdf, err := api.ReadContext(bg, rs, conf)
	if err != nil {
		return fmt.Errorf("%w: pdfcpu parse: %v", ErrInvalidContent, err)
	}
	if pdf == nil || pdf.XRefTable == nil {
		return fmt.Errorf("%w: PDF parsed to empty context", ErrInvalidContent)
	}
	// A PDF without a resolvable catalog can't be safely re-serialized —
	// pdfcpu will panic on Write. Reject before we get there.
	if _, err := pdf.XRefTable.Catalog(); err != nil {
		return fmt.Errorf("%w: PDF has no catalog: %v", ErrInvalidContent, err)
	}

	// Walk every object in the cross-reference table. Each XRef entry is a
	// top-level indirect object; nested Dicts/Arrays (inline, not via
	// indirect ref) are recursed into so we catch e.g. an inline /Names
	// subtree with a /JavaScript entry. IndirectRefs are not followed —
	// they'll be reached when we iterate their own XRef entry.
	for _, entry := range pdf.XRefTable.Table {
		if entry == nil || entry.Free || entry.Object == nil {
			continue
		}
		stripObject(entry.Object)
	}

	// RootDict is a cached copy of the catalog dict, used for top-level
	// lookups. Strip it too so a re-serialization that reads through
	// RootDict (rather than re-resolving Root) sees the cleaned version.
	if pdf.XRefTable.RootDict != nil {
		stripDict(pdf.XRefTable.RootDict)
	}

	if err := api.WriteContext(bg, pdf, w); err != nil {
		return fmt.Errorf("%w: pdfcpu serialize: %v", ErrInvalidContent, err)
	}
	return nil
}

// stripObject removes dangerous keys from an Object and recurses into any
// nested Dicts/Arrays/StreamDicts it contains. Safe to call with any
// types.Object; non-container types are no-ops.
func stripObject(obj types.Object) {
	switch o := obj.(type) {
	case types.Dict:
		stripDict(o)
	case *types.Dict:
		if o != nil {
			stripDict(*o)
		}
	case types.StreamDict:
		stripDict(o.Dict)
	case *types.StreamDict:
		if o != nil {
			stripDict(o.Dict)
		}
	case types.Array:
		for _, v := range o {
			stripObject(v)
		}
	case *types.Array:
		if o != nil {
			for _, v := range *o {
				stripObject(v)
			}
		}
	}
}

func stripDict(d types.Dict) {
	for _, k := range dangerousPDFKeys {
		d.Delete(k)
	}
	for _, v := range d {
		stripObject(v)
	}
}
