package com.libraryz.data

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The reader's alignment, indent and line height over the book's. */
class TextOverridesTest {

    private fun book(body: String, css: String = "") = parseEpub(
        epub(
            opf(item("a", "a.xhtml"), """<itemref idref="a"/>"""),
            "book/a.xhtml" to """<html><head><style>$css</style></head><body>$body</body></html>""",
        ),
    )

    private fun AnnotatedString.styleAt(of: String): ParagraphStyle? {
        val at = text.indexOf(of)
        return paragraphStyles.singleOrNull { at >= it.start && at < it.end }?.item
    }

    @Test
    fun theBookAsItIsChangesNothing() {
        val b = book("<p>One.</p><p>Two.</p>")
        assertSame(b.text, overridden(b.text, b.lineHeights, TextOverrides()))
    }

    @Test
    fun alignmentIsThePagesForAllButCentredAndRightAlignedText() {
        val b = book(
            "<h1>Title</h1><p class='j'>Justified.</p><p class='c'>Centred.</p><p class='r'>Right.</p>",
            css = ".j { text-align: justify } .c { text-align: center } .r { text-align: right }",
        )
        // The book's justify is left to the page; its centred and right-aligned text keeps its own.
        assertTrue(b.text.styleAt("Justified")?.textAlign.let { it == null || it == TextAlign.Unspecified })
        assertEquals(TextAlign.Center, b.text.styleAt("Centred")?.textAlign)
        assertEquals(TextAlign.End, b.text.styleAt("Right")?.textAlign)
        assertEquals(TextAlign.Center, b.text.styleAt("Title")?.textAlign)
        // The page's alignment: the reader's for its layout, unless the override says otherwise.
        assertEquals(TextAlign.Justify, TextOverrides().baseAlign(TextAlign.Justify))
        assertEquals(TextAlign.Start, TextOverrides(align = AlignOverride.Left).baseAlign(TextAlign.Justify))
        assertEquals(TextAlign.Justify, TextOverrides(align = AlignOverride.Justify).baseAlign(TextAlign.Unspecified))
        // Alignment alone changes no paragraph style.
        assertSame(b.text, overridden(b.text, b.lineHeights, TextOverrides(align = AlignOverride.Justify)))
    }

    @Test
    fun indentGoesOnEveryParagraphButHeadingsCentredLinesAndPictures() {
        val b = book(
            "<h2 style='text-align: left'>Head</h2><p>One.</p><p>Two.</p><p class='c'>Centred.</p><blockquote><p>Quoted.</p></blockquote>",
            css = "p { margin: 0 } .c { text-align: center }",
        )
        val t = overridden(b.text, b.lineHeights, TextOverrides(indent = IndentOverride.Indent))
        assertEquals(TextIndent(2.em, 0.em), t.styleAt("One")?.textIndent)
        // A paragraph of its own, though the book ran One and Two together in one region.
        assertEquals(TextIndent(2.em, 0.em), t.styleAt("Two")?.textIndent)
        assertTrue(t.text.indexOf("Two") in t.paragraphStyles.map { it.start }, "Two starts a range")
        // Same length, so every offset (pages, boxes, chapters) still holds.
        assertEquals(b.text.length, t.length)
        // In from the quote's margin, by 2 em.
        assertEquals(TextIndent(3.5.em, 1.5.em), t.styleAt("Quoted")?.textIndent)
        assertNull(t.styleAt("Head")?.textIndent)
        assertNull(t.styleAt("Centred")?.textIndent)
        // Ranges never overlap, so Compose lays them out.
        t.paragraphStyles.sortedBy { it.start }.zipWithNext().forEach { (a, z) -> assertTrue(a.end <= z.start, "$a overlaps $z") }

        val none = overridden(
            book("<p class='i'>Indented.</p>", css = ".i { text-indent: 3em }").let { overridden(it.text, it.lineHeights, TextOverrides()) },
            emptyList(), TextOverrides(indent = IndentOverride.None),
        )
        assertEquals(TextIndent(0.em, 0.em), none.styleAt("Indented")?.textIndent)
    }

    @Test
    fun theBooksLineHeightsCountOnlyIfAsked() {
        val b = book("<h1>Big</h1><p>Body text.</p>", css = "body { line-height: 1.8 } h1 { font-size: 2em }")
        assertTrue(b.lineHeights.isNotEmpty())
        // Kept in the parsed text, so changing the setting never re-reads the book...
        assertEquals(1.8.em.value, b.text.styleAt("Body")!!.lineHeight.value, 1e-4f)
        // ...and off by default: the reader's spacing for body text, a heading's own taller lines for headings.
        val off = overridden(b.text, b.lineHeights, TextOverrides())
        assertEquals(TextUnit.Unspecified, off.styleAt("Body")?.lineHeight ?: TextUnit.Unspecified)
        assertEquals(2f * 1.3f, off.styleAt("Big")!!.lineHeight.value, 1e-4f)
        val on = overridden(b.text, b.lineHeights, TextOverrides(bookLineHeight = true))
        assertEquals(1.8f, on.styleAt("Body")!!.lineHeight.value, 1e-4f)
        assertEquals(1.8f * 2f, on.styleAt("Big")!!.lineHeight.value, 1e-4f)
    }

    @Test
    fun readsLineHeights() {
        assertEquals(1.5f, BookBuilder.lineHeightOf("1.5", 1f)!!.value, 1e-4f)
        assertEquals(1.2f, BookBuilder.lineHeightOf("120%", 1f)!!.value, 1e-4f)
        assertEquals(3f, BookBuilder.lineHeightOf("1.5em", 2f)!!.value, 1e-4f)
        // 24px at 16px to the em: 1.5 em, whatever the element's size.
        assertEquals(1.5f, BookBuilder.lineHeightOf("24px", 1.5f)!!.value, 1e-4f)
        assertNull(BookBuilder.lineHeightOf("normal", 1f))
        assertNull(BookBuilder.lineHeightOf("0", 1f))
        assertEquals(3f, BookBuilder.lineHeightOf("9", 1f)!!.value, 1e-4f)
    }
}
