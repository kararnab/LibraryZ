package com.libraryz.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How a table's columns share the width: automatic layout with the book's widths, and fixed layout. */
class ColumnWidthsTest {

    private fun auto(min: List<Float>, max: List<Float>, available: Float, specified: List<Float?> = min.map { null }, table: Float? = null) =
        columnWidths(min.toFloatArray(), max.toFloatArray(), specified, table, available, fixed = false, minColumn = 10f)

    private fun fixed(n: Int, available: Float, specified: List<Float?> = List(n) { null }, table: Float? = null) =
        columnWidths(FloatArray(n) { 50f }, FloatArray(n) { 500f }, specified, table, available, fixed = true, minColumn = 10f)

    private fun assertWidths(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertTrue(kotlin.math.abs(e - a) < 0.01f, "expected $expected, was $actual") }
    }

    @Test
    fun contentSizesColumnsWhenNothingIsSet() {
        assertWidths(listOf(100f, 200f), auto(listOf(50f, 50f), listOf(100f, 200f), available = 400f))
        // Room above the narrowest shared in proportion: 100 spare of 150 wanted.
        assertWidths(listOf(50f + 50f * 2 / 3, 50f + 100f * 2 / 3), auto(listOf(50f, 50f), listOf(100f, 150f), available = 200f))
    }

    @Test
    fun theBooksWidthsWin() {
        // A set width replaces the widest content, and raises the narrowest.
        assertWidths(listOf(150f, 200f), auto(listOf(50f, 50f), listOf(100f, 200f), available = 400f, specified = listOf(150f, null)))
        // But never below the longest word.
        assertWidths(listOf(80f, 200f), auto(listOf(80f, 50f), listOf(100f, 200f), available = 400f, specified = listOf(20f, null)))
        // A table the book makes full width: unsized columns take the extra.
        assertWidths(listOf(100f, 300f), auto(listOf(50f, 50f), listOf(100f, 100f), available = 400f, specified = listOf(100f, null), table = 400f))
    }

    @Test
    fun onlyWordsThatCantFitBreak() {
        // 40 + 60 + 300 can't fit in 200: the two narrow columns keep their longest word,
        // only the wide one is narrowed (the old proportional scaling broke all three).
        assertWidths(listOf(40f, 60f, 100f), auto(listOf(40f, 60f, 300f), listOf(40f, 60f, 300f), available = 200f))
        // The book's widths give way before any word breaks.
        assertWidths(listOf(150f, 50f), auto(listOf(50f, 50f), listOf(50f, 50f), available = 200f, specified = listOf(250f, null)))
    }

    @Test
    fun fixedLayoutIgnoresContent() {
        assertWidths(listOf(100f, 100f, 100f), fixed(3, available = 300f))
        assertWidths(listOf(150f, 75f, 75f), fixed(3, available = 300f, specified = listOf(150f, null, null)))
        // All set and narrower than the table: scaled up. Set wider than the page: scaled down.
        assertWidths(listOf(100f, 200f), fixed(2, available = 300f, specified = listOf(50f, 100f)))
        assertWidths(listOf(200f, 100f), fixed(2, available = 300f, specified = listOf(400f, 200f)))
        // The book's narrower table width.
        assertWidths(listOf(75f, 75f), fixed(2, available = 300f, table = 150f))
    }
}
