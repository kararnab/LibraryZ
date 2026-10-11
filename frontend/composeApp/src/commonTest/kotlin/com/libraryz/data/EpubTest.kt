package com.libraryz.data

import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A minimal zip with every entry stored (no compression), so a test can
 * spell out an EPUB's files. CRCs are left 0: the reader doesn't check them.
 */
private fun storedZip(vararg files: Pair<String, String>): ByteArray {
    val out = ArrayList<Byte>()
    val central = ArrayList<Byte>()
    fun MutableList<Byte>.u16(v: Int) { add(v.toByte()); add((v shr 8).toByte()) }
    fun MutableList<Byte>.u32(v: Int) { u16(v and 0xFFFF); u16(v ushr 16) }
    for ((name, body) in files) {
        val n = name.encodeToByteArray()
        val b = body.encodeToByteArray()
        val offset = out.size
        out.u32(0x04034b50); out.u16(20); out.u16(0); out.u16(0); out.u32(0); out.u32(0)
        out.u32(b.size); out.u32(b.size); out.u16(n.size); out.u16(0)
        out.addAll(n.toList()); out.addAll(b.toList())
        central.u32(0x02014b50); central.u16(20); central.u16(20); central.u16(0); central.u16(0); central.u32(0); central.u32(0)
        central.u32(b.size); central.u32(b.size); central.u16(n.size); central.u16(0); central.u16(0)
        central.u16(0); central.u16(0); central.u32(0); central.u32(offset)
        central.addAll(n.toList())
    }
    val cdOffset = out.size
    out.addAll(central)
    out.u32(0x06054b50); out.u16(0); out.u16(0); out.u16(files.size); out.u16(files.size)
    out.u32(central.size); out.u32(cdOffset); out.u16(0)
    return out.toByteArray()
}

private val CONTAINER = "META-INF/container.xml" to
    """<container><rootfiles><rootfile full-path="book/content.opf"/></rootfiles></container>"""

private fun epub(opf: String, vararg docs: Pair<String, String>): ByteArray =
    storedZip("mimetype" to "application/epub+zip", CONTAINER, "book/content.opf" to opf, *docs)

private fun opf(manifest: String, spine: String, toc: String = "") =
    """<package><manifest>$manifest</manifest><spine $toc>$spine</spine></package>"""

private fun item(id: String, href: String, type: String = "application/xhtml+xml", props: String = "") =
    """<item id="$id" href="$href" media-type="$type" properties="$props"/>"""

class EpubTest {

    @Test
    fun readsADeflatedEpubWithANavDocument() {
        val book = parseEpub(FIXTURE)
        val text = book.text.text
        assertTrue(text.startsWith("One\n\nIt was a dark and stormy night; the rain fell in torrents."), text.take(80))
        assertTrue(text.endsWith("Intro.\n\nTwo\n\nThe end."), text.takeLast(40))
        // The head's title and style aren't text.
        assertTrue("Ignored" !in text && "color" !in text)
        // The toc nav, not the landmarks one; hrefs percent-decoded and fragments resolved.
        assertEquals(listOf("One", "Two & more"), book.chapters.map { it.title })
        assertEquals(0, book.chapters[0].offset)
        assertEquals(text.lastIndexOf("Two"), book.chapters[1].offset)
    }

    @Test
    fun keepsInlineStylesAndCentersHeadings() {
        val book = parseEpub(FIXTURE)
        val text = book.text
        val end = text.text.indexOf("end")
        val italic = text.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
        assertEquals(end to end + 3, italic.start to italic.end)
        val centered = text.paragraphStyles.filter { (it.item as ParagraphStyle).textAlign == TextAlign.Center }
        assertEquals(listOf("One", "Two"), centered.map { text.text.substring(it.start, it.end) })
    }

    @Test
    fun fallsBackToTheNcxThenToHeadings() {
        val ncx = """<ncx><navMap>
            <navPoint id="a"><navLabel><text>Part I</text></navLabel><content src="t/a.html"/>
              <navPoint id="b"><navLabel><text>Chapter 1</text></navLabel><content src="t/a.html#c1"/></navPoint>
            </navPoint></navMap></ncx>"""
        val a = "t/a.html" to """<html><body><p>Front.</p><div id="c1"><p>First chapter.</p></div></body></html>"""
        val withNcx = parseEpub(
            epub(
                opf(item("a", "t/a.html", "text/html") + item("n", "toc.ncx", "application/x-dtbncx+xml"), """<itemref idref="a"/>""", """toc="n""""),
                "book/" + a.first to a.second, "book/toc.ncx" to ncx,
            ),
        )
        assertEquals(listOf(Chapter(0, "Part I", 0), Chapter(withNcx.text.text.indexOf("First"), "Chapter 1", 1)), withNcx.chapters)

        val noToc = parseEpub(
            epub(
                opf(item("a", "a.xhtml"), """<itemref idref="a"/>"""),
                "book/a.xhtml" to "<body><h1>Title</h1><p>x</p><h2>Sub <i>part</i></h2><p>y</p><h4>Too deep</h4></body>",
            ),
        )
        assertEquals(listOf("Title", "Sub part"), noToc.chapters.map { it.title })
        assertEquals(listOf(0, 1), noToc.chapters.map { it.depth })
    }

    @Test
    fun collapsesWhitespaceAndLaysOutBlocks() {
        val body = """<body>
            <p>  Hello,
               <b>world</b>!  </p>
            <div><div><p>Nested</p></div></div>
            <p>Line one<br/>line two</p>
            <h3><img src="x.png"/><br/>Leading break<br/></h3>
            <ol start="3"><li>three</li><li><p>four</p></li></ol>
            <ul><li>dot</li></ul>
            <hr/>
            <pre>  keep
  this</pre>
            <p>a&nbsp;b &amp; c &#8212; &#x2019; &unknown;</p>
            <p>Rule<hr/>after</p>
            <ul><li><ul><li>inner</li></ul></li></ul>
            <p>Unclosed <em>em<p>Next</body>"""
        val book = parseEpub(epub(opf(item("a", "a.xhtml"), """<itemref idref="a"/>"""), "book/a.xhtml" to body))
        assertEquals(
            "Hello, world!\n\nNested\n\nLine one\nline two\n\nLeading break\n\n3. three\n\n4. four\n\n• dot\n\n*   *   *\n\n" +
                "  keep\n  this\n\na\u00A0b & c — ’ &unknown;\n\nRule\n\n*   *   *\n\nafter\n\n• \n\n    • inner\n\n" +
                    "Unclosed em\n\nNext",
            book.text.text,
        )
    }

    @Test
    fun documentsFollowTheSpineNotTheManifest() {
        val book = parseEpub(
            epub(
                opf(item("a", "a.xhtml") + item("b", "b.xhtml") + item("img", "c.png", "image/png"), """<itemref idref="b"/><itemref idref="img"/><itemref idref="a"/>"""),
                "book/a.xhtml" to "<p>A</p>", "book/b.xhtml" to "<p>B</p>",
            ),
        )
        assertEquals("B\n\nA", book.text.text)
    }

    @Test
    fun rejectsWhatItCannotRead() {
        assertFailsWith<EpubException> { parseEpub("not a zip".encodeToByteArray()) }
        val imagesOnly = epub(opf(item("a", "a.xhtml"), """<itemref idref="a"/>"""), "book/a.xhtml" to """<body><img src="p.png"/></body>""")
        assertFailsWith<EpubException> { parseEpub(imagesOnly) }
    }

    @Test
    fun resolvesRelativePaths() {
        assertEquals("OEBPS/Images/a b.png", resolvePath("OEBPS/Text/", "../Images/a%20b.png"))
        assertEquals("x.xhtml", resolvePath("", "./x.xhtml"))
        assertEquals("root.xhtml", resolvePath("OEBPS/", "/root.xhtml"))
    }

    @Test
    fun tokenizesLenientMarkup() {
        val tokens = markupTokens("""<!DOCTYPE html><!-- c --><xhtml:P class=x id='y' hidden>a < b<![CDATA[<raw>]]></P><br/>""")
        assertEquals(
            listOf(
                MarkupToken.Start("p", mapOf("class" to "x", "id" to "y", "hidden" to ""), selfClosing = false),
                MarkupToken.Text("a "), MarkupToken.Text("<"), MarkupToken.Text(" b"), MarkupToken.Text("<raw>"),
                MarkupToken.End("p"),
                MarkupToken.Start("br", emptyMap(), selfClosing = true),
            ),
            tokens,
        )
    }

    @Test
    fun inflatesStoredFixedAndDynamicBlocks() {
        // zlib.compress(..., level)[2:-4] in Python: raw deflate.
        val stored = intArrayOf(0x01, 0x05, 0x00, 0xfa, 0xff, 0x68, 0x65, 0x6c, 0x6c, 0x6f)
        assertEquals("hello", inflate(stored.map { it.toByte() }.toByteArray()).decodeToString())
        assertFailsWith<ZipException> { inflate(stored.map { it.toByte() }.toByteArray().copyOf(7)) }
        assertFailsWith<ZipException> { inflate(ByteArray(0)) }
    }

    @Test
    fun capsHowFarAnEntryInflates() {
        assertFailsWith<ZipException> { ZipArchive(FIXTURE, maxEntrySize = 100).readText("OEBPS/Text/chapter 1.xhtml") }
    }

    private companion object {
        @OptIn(ExperimentalEncodingApi::class)
        val FIXTURE: ByteArray = Base64.decode(
            "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",
        )
    }
}
