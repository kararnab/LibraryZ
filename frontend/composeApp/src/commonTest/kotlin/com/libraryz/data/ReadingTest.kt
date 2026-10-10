package com.libraryz.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun edition(id: String, format: String) =
    Edition(id = id, workId = "w", format = format, sizeBytes = 1, sha256 = id)

private fun entry(
    status: String = LibraryStatus.Reading,
    currentPage: Int = 0,
    totalPages: Int = 0,
    progressPercent: Int = 0,
) = UserBook(
    id = "ub", userId = 1, workId = "w", status = status,
    currentPage = currentPage, totalPages = totalPages, progressPercent = progressPercent,
)

class ReadingTest {

    @Test
    fun picksTextWhenItIsTheOnlyReadableEdition() {
        // EPUB isn't readable in-app; TXT is everywhere.
        val picked = pickReadableEdition(listOf(edition("e1", "EPUB"), edition("e2", "TXT")))
        assertEquals("e2", picked?.id)
    }

    @Test
    fun picksNothingWhenNoEditionIsReadable() {
        assertNull(pickReadableEdition(listOf(edition("e1", "EPUB"))))
        assertNull(pickReadableEdition(emptyList()))
    }

    @Test
    fun prefersPdfWhenThisBuildCanRenderIt() {
        val picked = pickReadableEdition(listOf(edition("t", "TXT"), edition("p", "PDF")))
        assertEquals(if (isPdfPreviewSupported) "p" else "t", picked?.id)
    }

    @Test
    fun resumesAtTheSavedPageOfTheSameEdition() {
        assertEquals(41, resumePage(entry(currentPage = 42, totalPages = 300), 300))
    }

    @Test
    fun fallsBackToPercentWhenPageCountDiffers() {
        // Saved against a 300-page PDF, now opening a 200-page one at 50%.
        assertEquals(100, resumePage(entry(currentPage = 150, totalPages = 300, progressPercent = 50), 200))
    }

    @Test
    fun startsAtTheBeginningWithoutHistoryOrWhenFinished() {
        assertEquals(0, resumePage(null, 300))
        assertEquals(0, resumePage(entry(), 300))
        // 100% with no matching page: re-reading starts over.
        assertEquals(0, resumePage(entry(status = LibraryStatus.Read, progressPercent = 100), 300))
        assertEquals(0, resumePage(entry(currentPage = 5, totalPages = 10), 0))
    }

    @Test
    fun positionPercentCountsTheCurrentPageAsRead() {
        assertEquals(1, ReadingPosition(0, 100).percent)
        assertEquals(50, ReadingPosition(49, 100).percent)
        assertEquals(100, ReadingPosition(99, 100).percent)
        assertTrue(ReadingPosition(99, 100).isAtEnd)
        assertEquals(0, ReadingPosition(0, 0).percent)
    }

    @Test
    fun progressStartsReadingButNeverUnfinishes() {
        val pos = ReadingPosition(9, 100)
        assertEquals(LibraryStatus.Reading, progressUpdate(null, pos).status)
        assertEquals(LibraryStatus.Reading, progressUpdate(entry(status = LibraryStatus.Want), pos).status)
        assertNull(progressUpdate(entry(status = LibraryStatus.Reading), pos).status)
        assertNull(progressUpdate(entry(status = LibraryStatus.Read), pos).status)

        val req = progressUpdate(null, pos)
        assertEquals(10, req.currentPage)
        assertEquals(100, req.totalPages)
        assertEquals(10, req.progressPercent)
    }

    @Test
    fun readLabelOffersToContinueOnlyMidBook() {
        assertEquals("Start reading", readActionLabel(null))
        assertEquals("Start reading", readActionLabel(entry(status = LibraryStatus.Want)))
        assertEquals("Continue reading · 42%", readActionLabel(entry(progressPercent = 42)))
    }
}
