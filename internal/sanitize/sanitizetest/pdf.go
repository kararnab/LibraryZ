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
	return objStmPDF("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>", true, nil, padBytes)
}

// ObjStmPDF returns a one-page PDF 1.5 laid out the way Acrobat Distiller
// and most modern producers write one: the page (object 3, a dict with
// /Parent 2 0 R) is a plain object, and streamed (objects 6, 7, …, for
// pageDict to reference) live in a compressed object stream.
func ObjStmPDF(pageDict string, streamed ...string) []byte {
	return objStmPDF(pageDict, false, streamed, 0)
}

func objStmPDF(pageDict string, pageInStream bool, streamed []string, padBytes int) []byte {
	// Object stream layout: "objNr offset" pairs, then the objects.
	inStream := map[int]string{}
	var order []int
	if pageInStream {
		inStream[3] = pageDict
		order = append(order, 3)
	}
	for i, o := range streamed {
		inStream[6+i] = o
		order = append(order, 6+i)
	}
	var hdr, objs bytes.Buffer
	for _, nr := range order {
		fmt.Fprintf(&hdr, "%d %d ", nr, objs.Len())
		objs.WriteString(inStream[nr] + " ")
	}
	var z bytes.Buffer
	zw, _ := zlib.NewWriterLevel(&z, zlib.BestCompression)
	zw.Write(hdr.Bytes())
	zw.Write(objs.Bytes())
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
	if !pageInStream {
		obj(3, []byte(pageDict))
	}
	stm := fmt.Sprintf("<< /Type /ObjStm /N %d /First %d /Filter /FlateDecode /Length %d >>\nstream\n", len(order), hdr.Len(), z.Len())
	obj(4, append(append([]byte(stm), z.Bytes()...), "\nendstream"...))

	// Cross-reference stream (object 5): W [1 4 2]. Type 2 rows point into
	// object stream 4 by index.
	xrefAt := f.Len()
	type row struct {
		typ byte
		f2  uint32
		f3  uint16
	}
	at := func(nr int) row {
		for i, n := range order {
			if n == nr {
				return row{2, 4, uint16(i)}
			}
		}
		return row{1, uint32(offs[nr]), 0}
	}
	rows := []row{{0, 0, 65535}, at(1), at(2), at(3), at(4), {1, uint32(xrefAt), 0}}
	for i := range streamed {
		rows = append(rows, at(6+i))
	}
	var xs bytes.Buffer
	for _, r := range rows {
		xs.WriteByte(r.typ)
		binary.Write(&xs, binary.BigEndian, r.f2)
		binary.Write(&xs, binary.BigEndian, r.f3)
	}
	fmt.Fprintf(&f, "5 0 obj\n<< /Type /XRef /Size %d /W [1 4 2] /Root 1 0 R /Length %d >>\nstream\n", len(rows), xs.Len())
	f.Write(xs.Bytes())
	fmt.Fprintf(&f, "\nendstream\nendobj\nstartxref\n%d\n%%%%EOF\n", xrefAt)
	return f.Bytes()
}
