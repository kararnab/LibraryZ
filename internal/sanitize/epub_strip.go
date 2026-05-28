package sanitize

import (
	"archive/zip"
	"fmt"
	"io"
	"strings"

	"golang.org/x/net/html"
)

// scanEPUBForScripts is the strict-reject pass for EPUB active content. It
// returns ErrActiveContent on the first script-bearing entry it finds.
// Clean EPUBs return nil and the original reader is passed through
// unchanged at the caller (we don't rewrite the zip — preserving sha
// continuity for clean uploads is worth it).
//
// Three checks per entry:
//  1. Filename: any .js entry is a script asset, reject immediately.
//  2. OPF manifest media-types: text/javascript, application/javascript,
//     application/ecmascript — reject.
//  3. (X)HTML body: parse with x/net/html and look for <script> elements,
//     on* event handler attributes, and javascript: URLs in href/src.
//
// Parsing is forgiving — x/net/html will accept malformed XHTML that a
// strict XML parser wouldn't, which is what we want here: contributors
// shouldn't be able to bypass the check by submitting almost-XHTML.
func scanEPUBForScripts(rs io.ReadSeeker, size int64) error {
	ra, ok := rs.(io.ReaderAt)
	if !ok {
		return fmt.Errorf("%w: EPUB scanner requires io.ReaderAt", ErrInvalidContent)
	}
	zr, err := zip.NewReader(ra, size)
	if err != nil {
		return fmt.Errorf("%w: not a valid zip: %v", ErrFormatMismatch, err)
	}

	for _, f := range zr.File {
		name := f.Name
		lower := strings.ToLower(name)

		if strings.HasSuffix(lower, ".js") || strings.HasSuffix(lower, ".mjs") {
			return fmt.Errorf("%w: EPUB contains script file %q", ErrActiveContent, name)
		}

		// Only parse HTML-ish entries — skip images, fonts, CSS (CSS can
		// embed expressions in IE6-era browsers, but Compose / pdf.js /
		// PDFKit / iOS WebKit don't execute them in our reader path).
		switch {
		case strings.HasSuffix(lower, ".xhtml"),
			strings.HasSuffix(lower, ".html"),
			strings.HasSuffix(lower, ".htm"):
			if err := checkEPUBHTMLEntry(f, name); err != nil {
				return err
			}
		case strings.HasSuffix(lower, ".opf"):
			if err := checkEPUBOPFEntry(f, name); err != nil {
				return err
			}
		}
	}
	return nil
}

func checkEPUBHTMLEntry(f *zip.File, name string) error {
	rc, err := f.Open()
	if err != nil {
		return fmt.Errorf("%w: open %q: %v", ErrInvalidContent, name, err)
	}
	defer rc.Close()
	// Cap parsing at 16 MiB per file — way more than any legitimate XHTML
	// chapter, but bounds memory if a malicious EPUB ships a giant entry.
	root, err := html.Parse(io.LimitReader(rc, 16<<20))
	if err != nil {
		return fmt.Errorf("%w: parse %q: %v", ErrInvalidContent, name, err)
	}
	return walkHTMLForScripts(root, name)
}

func walkHTMLForScripts(n *html.Node, entryName string) error {
	if n.Type == html.ElementNode {
		if strings.EqualFold(n.Data, "script") {
			return fmt.Errorf("%w: %q contains <script>", ErrActiveContent, entryName)
		}
		for _, attr := range n.Attr {
			key := strings.ToLower(attr.Key)
			// on* event handlers (onclick, onload, onerror, ...).
			// Only flag those — a literal attribute named "on" doesn't
			// exist in HTML, but "on" alone wouldn't trigger anything
			// anyway, so require at least one trailing char.
			if len(key) > 2 && strings.HasPrefix(key, "on") {
				return fmt.Errorf("%w: %q has event handler attribute %s", ErrActiveContent, entryName, attr.Key)
			}
			// javascript: URLs in href/src/action/formaction/etc.
			if isURLAttr(key) && strings.HasPrefix(strings.TrimSpace(strings.ToLower(attr.Val)), "javascript:") {
				return fmt.Errorf("%w: %q has javascript: URL in %s", ErrActiveContent, entryName, attr.Key)
			}
		}
	}
	for c := n.FirstChild; c != nil; c = c.NextSibling {
		if err := walkHTMLForScripts(c, entryName); err != nil {
			return err
		}
	}
	return nil
}

func isURLAttr(lowerKey string) bool {
	switch lowerKey {
	case "href", "src", "action", "formaction", "data", "poster", "background":
		return true
	}
	return false
}

// checkEPUBOPFEntry parses the OPF manifest and rejects any item whose
// media-type declares JavaScript / ECMAScript. This catches scripts that
// don't end in .js (rare but valid per spec — e.g. .es or no extension).
func checkEPUBOPFEntry(f *zip.File, name string) error {
	rc, err := f.Open()
	if err != nil {
		return fmt.Errorf("%w: open %q: %v", ErrInvalidContent, name, err)
	}
	defer rc.Close()
	// OPFs are typically small (<100 KiB); cap to be safe.
	root, err := html.Parse(io.LimitReader(rc, 1<<20))
	if err != nil {
		return fmt.Errorf("%w: parse %q: %v", ErrInvalidContent, name, err)
	}
	return walkOPFForScriptMediaTypes(root, name)
}

func walkOPFForScriptMediaTypes(n *html.Node, entryName string) error {
	if n.Type == html.ElementNode && strings.EqualFold(n.Data, "item") {
		for _, attr := range n.Attr {
			if !strings.EqualFold(attr.Key, "media-type") {
				continue
			}
			mt := strings.ToLower(strings.TrimSpace(attr.Val))
			if mt == "text/javascript" || mt == "application/javascript" || mt == "application/ecmascript" {
				return fmt.Errorf("%w: OPF %q declares script item (media-type %s)", ErrActiveContent, entryName, attr.Val)
			}
		}
	}
	for c := n.FirstChild; c != nil; c = c.NextSibling {
		if err := walkOPFForScriptMediaTypes(c, entryName); err != nil {
			return err
		}
	}
	return nil
}
