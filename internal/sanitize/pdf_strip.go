package sanitize

import (
	"bytes"
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

func sanitizePDF(rs io.ReadSeeker) (cleaned io.Reader, retErr error) {
	// pdfcpu can panic deep inside its parser/writer on malformed PDFs
	// (saw a nil-pointer in writeRootObject for a header-only PDF with no
	// object graph). A malformed upload should never crash the server, so
	// recover and surface as ErrInvalidContent.
	defer func() {
		if r := recover(); r != nil {
			cleaned = nil
			retErr = fmt.Errorf("%w: pdfcpu panic: %v", ErrInvalidContent, r)
		}
	}()

	conf := model.NewDefaultConfiguration()
	conf.ValidationMode = model.ValidationRelaxed

	if _, err := rs.Seek(0, io.SeekStart); err != nil {
		return nil, fmt.Errorf("seek: %w", err)
	}
	ctx, err := api.ReadContext(rs, conf)
	if err != nil {
		return nil, fmt.Errorf("%w: pdfcpu parse: %v", ErrInvalidContent, err)
	}
	if ctx == nil || ctx.XRefTable == nil {
		return nil, fmt.Errorf("%w: PDF parsed to empty context", ErrInvalidContent)
	}
	// A PDF without a resolvable catalog can't be safely re-serialized —
	// pdfcpu will panic on Write. Reject before we get there.
	if _, err := ctx.XRefTable.Catalog(); err != nil {
		return nil, fmt.Errorf("%w: PDF has no catalog: %v", ErrInvalidContent, err)
	}

	// Walk every object in the cross-reference table. Each XRef entry is a
	// top-level indirect object; nested Dicts/Arrays (inline, not via
	// indirect ref) are recursed into so we catch e.g. an inline /Names
	// subtree with a /JavaScript entry. IndirectRefs are not followed —
	// they'll be reached when we iterate their own XRef entry.
	for _, entry := range ctx.XRefTable.Table {
		if entry == nil || entry.Free || entry.Object == nil {
			continue
		}
		stripObject(entry.Object)
	}

	// RootDict is a cached copy of the catalog dict, used for top-level
	// lookups. Strip it too so a re-serialization that reads through
	// RootDict (rather than re-resolving Root) sees the cleaned version.
	if ctx.XRefTable.RootDict != nil {
		stripDict(ctx.XRefTable.RootDict)
	}

	var buf bytes.Buffer
	if err := api.WriteContext(ctx, &buf); err != nil {
		return nil, fmt.Errorf("%w: pdfcpu serialize: %v", ErrInvalidContent, err)
	}
	return bytes.NewReader(buf.Bytes()), nil
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
