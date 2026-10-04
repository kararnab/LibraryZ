// Package sanitizetest holds fixtures for tests of internal/sanitize and
// its callers. Not for production use.
package sanitizetest

import (
	"bytes"
	"compress/zlib"
	"encoding/binary"
	"fmt"
)

// ObjStmBombPDF returns a small, structurally valid PDF 1.5 whose single
// compressed object stream inflates to more than padBytes. pdfcpu inflates
// object streams fully while parsing, so sanitizing this in-process costs
// several times padBytes of heap while the file itself is tiny (about
// padBytes/1000).
func ObjStmBombPDF(padBytes int) []byte {
	const hdr = "3 0 "
	var z bytes.Buffer
	zw, _ := zlib.NewWriterLevel(&z, zlib.BestCompression)
	zw.Write([]byte(hdr + "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>"))
	chunk := bytes.Repeat([]byte(" "), 1<<20)
	for left := padBytes; left > 0; left -= len(chunk) {
		if left < len(chunk) {
			chunk = chunk[:left]
		}
		zw.Write(chunk)
	}
	zw.Close()

	var f bytes.Buffer
	offs := map[int]int{}
	obj := func(n int, body []byte) {
		offs[n] = f.Len()
		fmt.Fprintf(&f, "%d 0 obj\n", n)
		f.Write(body)
		f.WriteString("\nendobj\n")
	}
	f.WriteString("%PDF-1.5\n")
	obj(1, []byte("<< /Type /Catalog /Pages 2 0 R >>"))
	obj(2, []byte("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"))
	stm := fmt.Sprintf("<< /Type /ObjStm /N 1 /First %d /Filter /FlateDecode /Length %d >>\nstream\n", len(hdr), z.Len())
	obj(4, append(append([]byte(stm), z.Bytes()...), "\nendstream"...))

	// Cross-reference stream (object 5): W [1 4 2]. Object 3 lives inside
	// object stream 4 at index 0.
	xrefAt := f.Len()
	type row struct {
		typ byte
		f2  uint32
		f3  uint16
	}
	rows := []row{{0, 0, 65535}, {1, uint32(offs[1]), 0}, {1, uint32(offs[2]), 0}, {2, 4, 0}, {1, uint32(offs[4]), 0}, {1, uint32(xrefAt), 0}}
	var xs bytes.Buffer
	for _, r := range rows {
		xs.WriteByte(r.typ)
		binary.Write(&xs, binary.BigEndian, r.f2)
		binary.Write(&xs, binary.BigEndian, r.f3)
	}
	fmt.Fprintf(&f, "5 0 obj\n<< /Type /XRef /Size 6 /W [1 4 2] /Root 1 0 R /Length %d >>\nstream\n", xs.Len())
	f.Write(xs.Bytes())
	fmt.Fprintf(&f, "\nendstream\nendobj\nstartxref\n%d\n%%%%EOF\n", xrefAt)
	return f.Bytes()
}
