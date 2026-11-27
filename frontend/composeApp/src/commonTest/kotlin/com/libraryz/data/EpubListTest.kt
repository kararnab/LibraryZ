package com.libraryz.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** List markers: list-style-type, `start`, `reversed`, `li[value]`. */
class EpubListTest {

    /** The text, blank lines (list spacing, tested elsewhere) left out. */
    private fun text(body: String, css: String = "") = parseEpub(
        epub(
            opf(item("a", "a.xhtml"), """<itemref idref="a"/>"""),
            "book/a.xhtml" to """<html><head><style>$css</style></head><body>$body</body></html>""",
        ),
    ).text.text.lines().filter { it.isNotBlank() }.joinToString("\n")

    @Test
    fun numbersCountFromStartAndValue() {
        assertEquals("3. A\n4. B", text("""<ol start="3"><li>A</li><li>B</li></ol>"""))
        assertEquals("1. A\n7. B\n8. C", text("""<ol><li>A</li><li value="7">B</li><li>C</li></ol>"""))
    }

    @Test
    fun aReversedListCountsDownFromItsOwnItems() {
        // Three items of its own; the nested list's don't count.
        assertEquals(
            "3. A\n2. B\n    1. x\n    2. y\n1. C",
            text("""<ol reversed=""><li>A</li><li>B<ol><li>x</li><li>y</li></ol></li><li>C</li></ol>"""),
        )
        assertEquals("10. A\n9. B", text("""<ol reversed="" start="10"><li>A</li><li>B</li></ol>"""))
    }

    @Test
    fun followsListStyleTypeTheItemsOwnFirst() {
        // The list hides markers, its items bring numbers back.
        assertEquals("1. A\n2. B", text("<ol><li>A</li><li>B</li></ol>", css = "ol, ul { list-style: none } ol li { list-style: decimal }"))
        assertEquals("A\nB", text("<ol><li>A</li><li>B</li></ol>", css = "ol { list-style-type: none }"))
        assertEquals("i. A\nii. B", text("<ol class='r'><li>A</li><li>B</li></ol>", css = ".r { list-style: lower-roman inside }"))
        assertEquals("a. A\nb. B", text("""<ol style="list-style-type: lower-alpha"><li>A</li><li>B</li></ol>"""))
        // Every list counts, numbered or not.
        assertEquals("1. A\n2. B", text("<ul><li>A</li><li>B</li></ul>", css = "ul { list-style-type: decimal }"))
        // Nothing further out than the list is inherited past the browser's own ol rule.
        assertEquals("1. A", text("<div class='toc'><ol><li>A</li></ol></div>", css = ".toc { list-style: none }"))
    }

    @Test
    fun bulletsChangeAsListsNest() {
        assertEquals("• A\n    ◦ B\n        ▪ C", text("<ul><li>A<ul><li>B<ul><li>C</li></ul></li></ul></li></ul>"))
        assertEquals("▪ A", text("<ul style='list-style: square'><li>A</li></ul>"))
    }

    @Test
    fun marker() {
        assertEquals("05. ", listMarker("decimal-leading-zero", 5, true))
        assertEquals("aa. ", listMarker("lower-alpha", 27, true))
        assertEquals("Z. ", listMarker("upper-latin", 26, true))
        assertEquals("XLII. ", listMarker("upper-roman", 42, true))
        assertEquals("τ. ", listMarker("lower-greek", 19, true))
        // Numbers a style can't write fall back to decimal, unknown styles too.
        assertEquals("0. ", listMarker("lower-alpha", 0, true))
        assertEquals("4000. ", listMarker("lower-roman", 4000, true))
        assertEquals("3. ", listMarker("cjk-decimal", 3, true))
        assertEquals("• ", listMarker("disclosure-closed", 3, false))
        assertEquals("– ", listMarker("\"– \"", 1, false))
        assertNull(listMarker("none", 1, true))
    }

    @Test
    fun readsTheShorthand() {
        assertEquals("square", listStyleType(mapOf("list-style" to "url(dot.png) square inside")))
        assertEquals("none", listStyleType(mapOf("list-style" to "none")))
        assertEquals("\"– \"", listStyleType(mapOf("list-style" to "\"– \" outside")))
        assertEquals("upper-roman", listStyleType(mapOf("list-style" to "none", "list-style-type" to "Upper-Roman")))
        assertNull(listStyleType(mapOf("list-style" to "inside")))
    }
}
