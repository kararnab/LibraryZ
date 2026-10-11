package com.libraryz.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The book's CSS: selectors, layout, colours and tables. */
class EpubStyleTest {

    /** One document, [body] in a page that links `../Styles/book.css` = [css] and carries [style] inline. */
    private fun book(body: String, css: String = "", style: String = "") = parseEpub(
        epub(
            opf(item("a", "Text/a.xhtml") + item("css", "Styles/book.css", "text/css"), """<itemref idref="a"/>"""),
            "book/Text/a.xhtml" to
                """<html><head><link rel="stylesheet" type="text/css" href="../Styles/book.css"/><style>$style</style></head><body>$body</body></html>""",
            "book/Styles/book.css" to css,
        ),
    )

    private fun AnnotatedString.paragraphAt(of: String): ParagraphStyle? {
        val at = text.indexOf(of)
        return paragraphStyles.singleOrNull { at >= it.start && at < it.end }?.item
    }

    private fun AnnotatedString.colorOf(of: String): Color? {
        val at = text.indexOf(of)
        return spanStyles.lastOrNull { at >= it.start && at < it.end && it.item.color != Color.Unspecified }?.item?.color
    }

    @Test
    fun matchesSelectorsBySpecificityAndOrder() {
        val sheet = Stylesheet(
            listOf(
                parseCss(
                    """
                    /* a comment */ @import url(x.css); @media print { p { color: red } }
                    p { color: blue; text-indent: 1em }
                    .note { color: green }
                    div > p.note { color: purple }
                    p.note { color: orange !important }
                    a:hover, p[lang], p + p { color: black }
                    section p { text-align: center }
                    """.trimIndent(),
                ),
            ),
        )
        fun e(name: String, cls: String = "", id: String? = null) = CssElement(name, id, cls.split(' ').filter { it.isNotEmpty() })
        val plain = sheet.declared(listOf(e("body"), e("p")), null)
        assertEquals("blue", plain["color"])
        assertNull(plain["text-align"])
        // !important beats the more specific rule and the inline style.
        assertEquals("orange", sheet.declared(listOf(e("div"), e("p", "note")), "color: teal")["color"])
        assertEquals("teal", sheet.declared(listOf(e("div"), e("p")), "color: teal")["color"])
        // Descendant, not just parent; '>' needs the parent.
        assertEquals("center", sheet.declared(listOf(e("section"), e("div"), e("p")), null)["text-align"])
        assertEquals("green", Stylesheet(listOf(parseCss(".note { color: green } div > .note { color: red }")))
            .declared(listOf(e("div"), e("section"), e("span", "note")), null)["color"])
    }

    @Test
    fun parsesColoursAndLengths() {
        assertEquals(Color(0xFFFF0000), cssColor("#f00"))
        assertEquals(Color(0xFF336699), cssColor("#336699"))
        assertEquals(Color(0x80336699), cssColor("#33669980"))
        assertEquals(Color(1f, 0f, 0f, 0.5f), cssColor("rgba(255, 0, 0, 0.5)"))
        assertEquals(Color(0xFFDC143C), cssColor("Crimson"))
        assertNull(cssColor("transparent"))
        assertEquals(Color(0xFFFFFF00), cssBackground("url(x.png) no-repeat yellow"))
        assertEquals(2f, cssEm("32px"))
        assertEquals(3f, cssEm("10%"))
        assertEquals(0f, cssEm("0"))
        assertNull(cssEm("auto"))
    }

    @Test
    fun followsAlignmentIndentsAndMargins() {
        val text = book(
            """<p class="c">Centered</p><p class="r">Right</p><p class="j">Justified</p>
               <blockquote><p>Quoted</p></blockquote>
               <p class="hang">Hanging</p>
               <p align="center">Attribute</p>""",
            css = ".c { text-align: center } .r { text-align: right } .j { text-align: justify }" +
                ".hang { margin-left: 2em; text-indent: -2em }",
        ).text
        assertEquals(TextAlign.Center, text.paragraphAt("Centered")?.textAlign)
        assertEquals(TextAlign.End, text.paragraphAt("Right")?.textAlign)
        // Justified and left text keep the reader's own alignment.
        assertNull(text.paragraphAt("Justified"))
        assertEquals(TextIndent(1.5.em, 1.5.em), text.paragraphAt("Quoted")?.textIndent)
        assertEquals(TextIndent(0.em, 2.em), text.paragraphAt("Hanging")?.textIndent)
        assertEquals(TextAlign.Center, text.paragraphAt("Attribute")?.textAlign)
        // Regions never overlap, so Compose lays them out as written.
        val ranges = text.paragraphStyles.sortedBy { it.start }
        ranges.zipWithNext().forEach { (a, b) -> assertTrue(a.end <= b.start, "$a overlaps $b") }
    }

    @Test
    fun indentedParagraphsWithoutMarginsHaveNoBlankLine() {
        val text = book(
            "<p>One.</p><p>Two.</p><p class='x'>Three.</p><div class='poem'><span class='l'>Line a</span><span class='l'>Line b</span></div>",
            css = "p { margin: 0; text-indent: 1em } p.x { margin-top: 1em } span.l { display: block }",
        ).text
        // Three. ends an indented region, so its trailing \n is the blank line before the poem.
        assertEquals("One.\nTwo.\n\nThree.\nLine a\nLine b", text.text)
        assertEquals(TextIndent(1.em, 0.em), text.paragraphAt("Two")?.textIndent)
    }

    @Test
    fun keepsColoursHighlightsAndHidesWhatTheBookHides() {
        val text = book(
            """<p>Plain <span class="key">key term</span> and <span style="color:#c00;font-weight:bold">inline</span>
               <mark>marked</mark> <font color="green">font</font> <span class="hl">high</span>
               <span class="hidden">secret</span><em class="up">upright</em></p>""",
            css = ".key { color: navy } .hl { background-color: #ffeb3b } .hidden { display: none } .up { font-style: normal }",
            style = "p .key { color: teal }",
        ).text
        assertEquals("Plain key term and inline marked font high upright", text.text)
        // The <style> block's rule is more specific than the stylesheet's.
        assertEquals(Color(0xFF008080), text.colorOf("key term"))
        assertEquals(Color(0xFFCC0000), text.colorOf("inline"))
        assertTrue(text.spanStyles.any { text.text.substring(it.start, it.end) == "inline" && it.item.fontWeight == FontWeight.Bold })
        assertEquals(Color(0xFF008000), text.colorOf("font"))
        assertTrue(text.spanStyles.any { text.text.substring(it.start, it.end) == "high" && it.item.background == Color(0xFFFFEB3B) })
        assertTrue(text.spanStyles.any { text.text.substring(it.start, it.end) == "marked" && it.item.background != Color.Unspecified })
        // The class's font-style: normal is applied after <em>'s italic, so it wins.
        val upright = text.spanStyles.filter { text.text.substring(it.start, it.end) == "upright" && it.item.fontStyle != null }
        assertEquals(FontStyle.Normal, upright.last().item.fontStyle)
    }

    @Test
    fun sizesTextAgainstTheReadersSize() {
        val text = book(
            """<p class="big">Big <span class="half">half</span></p><p>Plain <small>small</small></p><h1>Title</h1>""",
            css = "body { font-size: 80% } .big { font-size: 1.5em } .half { font-size: 50% } h1 { font-size: 3em }",
        ).text
        fun sizeOf(of: String) = text.spanStyles.lastOrNull { text.text.substring(it.start, it.end) == of && it.item.fontSize.isEm }?.item?.fontSize?.value
        // Factors multiply down the tree, against the reader's size: body's own size is ignored.
        assertEquals(1.5f, sizeOf("Big half"))
        assertEquals(0.75f, sizeOf("half"))
        assertEquals(0.85f, sizeOf("small"))
        // Clamped to 2.5×, with lines tall enough for it.
        assertEquals(2.5f, sizeOf("Title"))
        assertEquals((2.5f * 1.3f).em, text.paragraphAt("Title")?.lineHeight)
        assertEquals((1.5f * 1.3f).em, text.paragraphAt("Big")?.lineHeight)
    }

    @Test
    fun keepsTheBooksBoxes() {
        val book = book(
            """<p>Before</p><div class="note"><p>Remember this.</p><p>And this.</p></div><p>After</p>""",
            css = ".note { border: 1px solid #c62828; background-color: #ffebee; margin-left: 1em }",
        )
        val box = book.boxes.single()
        val text = book.text
        assertEquals("Remember this.\n\nAnd this.", text.text.substring(box.start, box.end))
        assertEquals(1f, box.left)
        assertTrue(box.border)
        assertEquals(Color(0xFFC62828), box.borderColor)
        assertEquals(Color(0xFFFFEBEE), box.background)
        // The box draws the background, so the text doesn't highlight it too; and the text sits inside the edge.
        assertTrue(text.spanStyles.none { it.item.background != Color.Unspecified })
        assertEquals(TextIndent(1.6.em, 1.6.em), text.paragraphAt("Remember")?.textIndent)
    }

    @Test
    fun readsFontFaces() {
        val faces = parseFontFaces(
            """@font-face { font-family: "Book Serif"; src: url('../Fonts/serif.otf') format("opentype"); font-weight: bold; font-style: italic }
               @font-face { font-family: Local; src: local(Arial) }
               @font-face { font-family: Data; src: url(data:font/ttf;base64,AA) }""",
        )
        val f = faces.single()
        assertEquals("book serif", f.family)
        assertEquals("../Fonts/serif.otf", f.src)
        assertEquals(700, f.weight)
        assertTrue(f.italic)
        assertEquals(listOf("book serif", "georgia", "serif"), cssFontFamilies("\"Book Serif\", Georgia, serif"))
        assertEquals(0.89f, cssFontScale("smaller"))
        assertEquals(1.25f, cssFontScale("125%"))
        assertNull(cssFontScale("calc(1em + 2px)"))
    }

    @Test
    fun undoesFontObfuscation() {
        // FIPS 180 test vectors.
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", sha1("abc".encodeToByteArray()).hex())
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", sha1(ByteArray(0)).hex())
        assertEquals("84983e441c3bd26ebaae4aa1f95129e5e54670f1", sha1("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).hex())
        val font = ByteArray(2000) { (it * 7).toByte() }
        for (scheme in FontObfuscation.entries) {
            val id = if (scheme == FontObfuscation.Adobe) "urn:uuid:0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0" else " urn:isbn: 978 0 \n"
            val hidden = font.copyOf()
            assertTrue(deobfuscate(hidden, scheme, id))
            assertTrue(!hidden.contentEquals(font))
            // XOR: the same operation undoes it; only the obfuscated prefix changes.
            assertTrue(hidden.copyOfRange(1040, 2000).contentEquals(font.copyOfRange(1040, 2000)))
            deobfuscate(hidden, scheme, id)
            assertTrue(hidden.contentEquals(font))
        }
        assertTrue(!deobfuscate(font.copyOf(), FontObfuscation.Adobe, "not a uuid"))
        assertTrue(isSfnt(byteArrayOf(0, 1, 0, 0) + ByteArray(8)))
        assertTrue(!isSfnt("wOFF".encodeToByteArray() + ByteArray(8)))
    }

    @Test
    fun readsSvgSizes() {
        fun size(tag: String) = svgSize("<?xml version='1.0'?><!-- x --><svg xmlns='http://www.w3.org/2000/svg' $tag></svg>".encodeToByteArray())
        assertEquals(120 to 80, size("width='120' height='80px'"))
        assertEquals(96 to 48, size("width='1in' viewBox='0 0 200 100'"))
        assertEquals(200 to 100, size("width='100%' height='100%' viewBox='0,0,200,100'"))
        assertEquals(300 to 150, size(""))
        assertEquals(64 to 32, svgSize("<svg:svg xmlns:svg='http://www.w3.org/2000/svg' width='64' height='32'/>".encodeToByteArray()))
        assertNull(svgSize("<html/>".encodeToByteArray()))
    }

    @Test
    fun readsTablesAsGrids() {
        val book = book(
            """<p>Before</p>
               <table border="1" id="t"><caption>Planets</caption>
                 <thead><tr><th>Name</th><th>Moons</th></tr></thead>
                 <tbody><tr><td class="em">Earth</td><td align="right">1</td></tr>
                 <tr><td colspan="2" style="background:#e0f7fa">Mars <b>2</b>
                   <table><tr><td>inner</td><td>cells</td></tr></table></td></tr></tbody>
               </table><p>After</p>""",
            css = ".em { color: red }",
        )
        val text = book.text
        val p = IMAGE_CHAR
        // Rows are one-line paragraphs of their own, so no newline between them; the blank line after leads the next block.
        assertEquals("Before\nPlanets\n$p$p$p\nAfter", text.text)
        val table = book.tables.single()
        assertTrue(table.bordered)
        assertEquals(listOf(2, 2, 1), table.rows.map { it.size })
        assertEquals(listOf(true, true), table.rows[0].map { it.header })
        assertEquals("Name", table.rows[0][0].text.text)
        assertEquals(Color(0xFFFF0000), table.rows[1][0].text.colorOf("Earth"))
        assertEquals(TextAlign.End, table.rows[1][1].text.paragraphStyles.single().item.textAlign)
        val mars = table.rows[2].single()
        assertEquals(2, mars.span)
        assertEquals(Color(0xFFE0F7FA), mars.background)
        // A table inside a cell is flattened to text.
        assertTrue(mars.text.text.startsWith("Mars 2") && "inner" in mars.text.text && "cells" in mars.text.text, mars.text.text)
        // One paragraph per row, so each line's height is the row's own on Android too.
        val rows = text.getStringAnnotations(TABLE_TAG, 0, text.length).map { it.start }
        rows.forEach { at -> assertTrue(text.paragraphStyles.any { it.start == at && it.end == at + 1 }, "row at $at") }
        val keys = text.getStringAnnotations(TABLE_TAG, 0, text.length).map { it.item }
        assertEquals(listOf("0:0", "0:1", "0:2"), keys)
        assertEquals(TextAlign.Center, text.paragraphAt("Planets")?.textAlign)
    }
}
