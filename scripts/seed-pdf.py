#!/usr/bin/env python3
"""Write a tiny, valid single-page PDF for the seed script.

Usage:  seed-pdf.py "<title>" "<authors>" "<out-path>"

The PDF is just a Helvetica title + authors + a "demo placeholder" line.
It's a real PDF — opens in PDFBox / PdfRenderer / pdf.js — but only a
few hundred bytes. Per-work uniqueness is automatic because the embedded
title text differs.
"""
import sys


def _latin1(s: str) -> bytes:
    # PDF text strings without an explicit encoding are PDFDocEncoding; we
    # stay inside ASCII-printable so we don't need to escape Unicode. Anything
    # exotic in the title gets a "?".
    return (
        s.replace("(", r"\(")
         .replace(")", r"\)")
         .encode("latin-1", "replace")
    )


def build(title: str, authors: str) -> bytes:
    stream = (
        b"BT /F1 18 Tf 72 720 Td (" + _latin1(title) + b") Tj ET\n"
        b"BT /F1 12 Tf 72 690 Td (" + _latin1(authors) + b") Tj ET\n"
        b"BT /F1 10 Tf 72 660 Td "
        b"(LibraryZ - demo placeholder edition) Tj ET\n"
    )

    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        (b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
         b"/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>"),
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        (b"<< /Length " + str(len(stream)).encode() + b" >>\n"
         b"stream\n" + stream + b"endstream"),
    ]

    out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = []
    for i, obj in enumerate(objects, start=1):
        offsets.append(len(out))
        out += f"{i} 0 obj\n".encode() + obj + b"\nendobj\n"

    xref_start = len(out)
    out += f"xref\n0 {len(objects) + 1}\n".encode()
    out += b"0000000000 65535 f \n"
    for off in offsets:
        out += f"{off:010d} 00000 n \n".encode()
    out += (
        f"trailer << /Size {len(objects) + 1} /Root 1 0 R >>\n"
        f"startxref\n{xref_start}\n%%EOF\n"
    ).encode()
    return bytes(out)


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.stderr.write("usage: seed-pdf.py <title> <authors> <out-path>\n")
        sys.exit(2)
    title, authors, out_path = sys.argv[1], sys.argv[2], sys.argv[3]
    with open(out_path, "wb") as f:
        f.write(build(title, authors))
