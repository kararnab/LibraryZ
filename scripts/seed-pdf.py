#!/usr/bin/env python3
"""Write a small, valid multi-page PDF for the seed script.

Usage:  seed-pdf.py "<title>" "<authors>" "<out-path>" ["<description>"]

A title page plus a few pages of clearly-marked placeholder text (Times,
wrapped by hand, page numbers in the footer), so the PagedReader has a
spread to show in two-page mode. It's a real PDF — opens in PDFBox /
PdfRenderer / pdf.js — but only a few KB. Per-work uniqueness is automatic
because the embedded title text differs.
"""
import sys
import textwrap

PAGE_W, PAGE_H = 612, 792
MARGIN = 72
BODY_SIZE, LEADING = 12, 18
WRAP = 86  # characters per line at 12pt Times over a 468pt measure


def _latin1(s: str) -> bytes:
    # PDF text strings without an explicit encoding are PDFDocEncoding; we
    # stay inside Latin-1 so we don't need to escape Unicode. Anything
    # exotic in the title gets a "?".
    s = s.replace("—", "-").replace("’", "'").replace("“", '"').replace("”", '"')
    return (
        s.replace("\\", r"\\")
         .replace("(", r"\(")
         .replace(")", r"\)")
         .encode("latin-1", "replace")
    )


def _text(font: bytes, size: int, x: float, y: float, s: str) -> bytes:
    return b"BT /" + font + b" " + str(size).encode() + b" Tf " + \
        f"{x:.1f} {y:.1f}".encode() + b" Td (" + _latin1(s) + b") Tj ET\n"


def _paragraphs(title: str, authors: str, desc: str) -> list:
    """(heading, body) pairs for the placeholder pages."""
    return [
        ("About the book", desc or f"{title}, by {authors}."),
        ("About this edition",
         "This is a demo PDF edition created by the LibraryZ seed script. It exists so the "
         "paged reader has something to render in screenshots and demos. Replace it with "
         "the real book by uploading a PDF through the app; every upload is sanitized out "
         "of process before it is stored."),
        ("How it is read",
         "PDF editions go through the PagedReader: PDFBox on Desktop, PdfRenderer on "
         "Android, pdf.js on the web and PDFKit on iOS. Each page is rasterized at the "
         "size it is shown. On a wide window the reader lays pages out in pairs, like a "
         "printed book: the title page sits alone on the right, and later pages follow "
         "left and right. Narrow windows and phones show a single page."),
        ("Reading position",
         "Your place in the book is saved to your library as you turn pages, so Continue "
         "reading on the Browse screen and on the book's page takes you back where you "
         "left off, on any device signed in to the same account."),
        ("Contributing",
         "Anyone signed in can suggest a fix to a book's details: a title, a subtitle, the "
         "authors, the year or the description. Moderators see each suggestion as a "
         "word-level diff and approve or reject it, so the catalog improves over time "
         "without anyone editing it directly."),
        ("Recommendations",
         "The For You screen is driven by a small matrix-factorization model trained on "
         "what readers shelve, finish and rate. Until you have a history, it falls back "
         "to what is popular on this LibraryZ instance."),
    ]


def _pages(title: str, authors: str, desc: str) -> list:
    pages = []

    # Title page.
    s = bytearray()
    y = PAGE_H - 220
    for line in textwrap.wrap(title, 30):
        s += _text(b"F2", 28, MARGIN, y, line)
        y -= 36
    y -= 12
    s += _text(b"F3", 14, MARGIN, y, authors.replace("; ", ", "))
    s += _text(b"F1", 10, MARGIN, 96, "LibraryZ - demo placeholder edition")
    pages.append(bytes(s))

    # Body pages: headings + wrapped paragraphs, three sections per page.
    sections = _paragraphs(title, authors, desc)
    for i in range(0, len(sections), 3):
        s = bytearray()
        y = PAGE_H - MARGIN - 20
        for heading, body in sections[i:i + 3]:
            s += _text(b"F2", 16, MARGIN, y, heading)
            y -= 28
            for line in textwrap.wrap(body, WRAP):
                s += _text(b"F1", BODY_SIZE, MARGIN, y, line)
                y -= LEADING
            y -= 26
        n = len(pages) + 1
        s += _text(b"F1", 9, PAGE_W / 2 - 4, 48, str(n))
        pages.append(bytes(s))
    return pages


def build(title: str, authors: str, desc: str = "") -> bytes:
    contents = _pages(title, authors, desc)
    n = len(contents)
    # 1 catalog, 2 pages, 3-5 fonts, then (page, contents) pairs.
    kids = " ".join(f"{6 + 2 * i} 0 R" for i in range(n)).encode()
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [" + kids + b"] /Count " + str(n).encode() + b" >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Times-Roman /Encoding /WinAnsiEncoding >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Times-Bold /Encoding /WinAnsiEncoding >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Times-Italic /Encoding /WinAnsiEncoding >>",
    ]
    for i, stream in enumerate(contents):
        objects.append(
            b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
            b"/Resources << /Font << /F1 3 0 R /F2 4 0 R /F3 5 0 R >> >> "
            b"/Contents " + str(7 + 2 * i).encode() + b" 0 R >>"
        )
        objects.append(
            b"<< /Length " + str(len(stream)).encode() + b" >>\n"
            b"stream\n" + stream + b"endstream"
        )

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
    if len(sys.argv) not in (4, 5):
        sys.stderr.write("usage: seed-pdf.py <title> <authors> <out-path> [<description>]\n")
        sys.exit(2)
    title, authors, out_path = sys.argv[1], sys.argv[2], sys.argv[3]
    desc = sys.argv[4] if len(sys.argv) == 5 else ""
    with open(out_path, "wb") as f:
        f.write(build(title, authors, desc))
