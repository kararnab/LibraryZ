package com.libraryz.ui

import com.libraryz.data.Edition
import com.libraryz.data.Work
import com.libraryz.ui.components.bylineOf
import com.libraryz.ui.components.coverIndex
import com.libraryz.ui.components.formatsLabel
import com.libraryz.ui.components.monogram
import com.libraryz.ui.screens.dayMonth
import com.libraryz.ui.screens.monthYear
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesignHelpersTest {

    @Test
    fun coverClothMatchesTheDesignReference() {
        // Expected values from the design's JS hash (int32 wraparound), so a
        // book gets the same cloth in the mockups and in the app.
        assertEquals(6, coverIndex("Middlemarch"))
        assertEquals(1, coverIndex("Moby-Dick"))
        assertEquals(7, coverIndex("North and South"))
        assertEquals(5, coverIndex("Walden"))
        // Case-insensitive.
        assertEquals(coverIndex("walden"), coverIndex("WALDEN"))
    }

    @Test
    fun monogramSkipsLeadingArticles() {
        assertEquals("M", monogram("Middlemarch"))
        assertEquals("C", monogram("The Count of Monte Cristo"))
        assertEquals("T", monogram("A Tale of Two Cities"))
        assertEquals("?", monogram("  "))
    }

    @Test
    fun formatsAndBylineReadNaturally() {
        fun ed(f: String) = Edition(id = f, workId = "w", format = f, sizeBytes = 1, sha256 = f)
        assertEquals("PDF · Plain text", formatsLabel(listOf(ed("TXT"), ed("pdf"), ed("PDF"))))
        assertEquals("George Eliot · 1871", bylineOf(Work(id = "w", title = "M", authors = "George Eliot", publicationYear = 1871)))
        assertEquals("1871", bylineOf(Work(id = "w", title = "M", authors = " ", publicationYear = 1871)))
    }

    @Test
    fun libraryDatesAreShortAndForgiving() {
        assertEquals("3 Oct", dayMonth("2026-10-03T09:15:00Z"))
        assertEquals("Aug 2026", monthYear("2026-08-21T00:00:00.123+05:30"))
        assertNull(dayMonth(null))
        assertNull(monthYear("not a date"))
    }
}
