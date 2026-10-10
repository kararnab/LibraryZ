package com.libraryz.ui

import com.libraryz.data.api.ApiException
import com.libraryz.data.api.DuplicateEditionException
import com.libraryz.ui.screens.DiffKind
import com.libraryz.ui.screens.DiffSegment
import com.libraryz.ui.screens.FileProblem
import com.libraryz.ui.screens.MAX_UPLOAD_BYTES
import com.libraryz.ui.screens.UploadFailure
import com.libraryz.ui.screens.changeSummary
import com.libraryz.ui.screens.checkUploadFile
import com.libraryz.ui.screens.classifyUploadError
import com.libraryz.ui.screens.displayValue
import com.libraryz.ui.screens.joinNatural
import com.libraryz.ui.screens.message
import com.libraryz.ui.screens.submittedAt
import com.libraryz.ui.screens.wordDiff
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class FlowHelpersTest {

    @Test
    fun wordDiffMarksInsertedAndRemovedWords() {
        // The example from the design's review mockup.
        val old = "following Dorothea Brooke, the doctor Tertius Lydgate and the people around them."
        val new = "following the idealistic Dorothea Brooke, the ambitious young doctor Tertius Lydgate, and a community facing change."
        val d = wordDiff(old, new)
        assertEquals(
            listOf(
                DiffSegment("following ", DiffKind.Same),
                DiffSegment("the idealistic ", DiffKind.Added),
                DiffSegment("Dorothea Brooke, the ", DiffKind.Same),
                DiffSegment("ambitious young ", DiffKind.Added),
                DiffSegment("doctor Tertius ", DiffKind.Same),
                DiffSegment("Lydgate ", DiffKind.Removed),
                DiffSegment("Lydgate, ", DiffKind.Added),
                DiffSegment("and ", DiffKind.Same),
                DiffSegment("the people around them.", DiffKind.Removed),
                DiffSegment("a community facing change.", DiffKind.Added),
            ),
            d,
        )
        // The new side always reassembles exactly.
        assertEquals(new, d.filter { it.kind != DiffKind.Removed }.joinToString("") { it.text })
    }

    @Test
    fun wordDiffHandlesEmptySides() {
        assertEquals(listOf(DiffSegment("new text", DiffKind.Added)), wordDiff("", "new text"))
        assertEquals(listOf(DiffSegment("old", DiffKind.Removed)), wordDiff("old", ""))
        assertEquals(listOf(DiffSegment("same", DiffKind.Same)), wordDiff("same", "same"))
    }

    @Test
    fun wordDiffFallsBackForHugeTexts() {
        val big = (1..600).joinToString(" ") { "w$it" }
        val d = wordDiff(big, big + " more")
        assertEquals(listOf(DiffKind.Removed, DiffKind.Added), d.map { it.kind })
    }

    @Test
    fun uploadFileChecksMatchTheServer() {
        assertNull(checkUploadFile("middlemarch.pdf", 6_000_000))
        assertNull(checkUploadFile("Book.EPUB", 1))
        assertEquals(FileProblem.Unsupported("MOBI"), checkUploadFile("ulysses.mobi", 1))
        assertEquals(FileProblem.Unsupported("AZW3"), checkUploadFile("x.azw3", 1)) // not on the server's allowlist
        assertIs<FileProblem.Unsupported>(checkUploadFile("noextension", 1))
        assertEquals(FileProblem.TooLarge(MAX_UPLOAD_BYTES + 1), checkUploadFile("big.pdf", MAX_UPLOAD_BYTES + 1))
        assertEquals("MOBI files aren’t supported. Use PDF, EPUB or TXT.", FileProblem.Unsupported("MOBI").message())
    }

    @Test
    fun uploadErrorsMapToWhatTheSheetShows() {
        fun api(status: Int, body: String = "x") = ApiException(status, body, "upload edition failed")
        assertEquals(UploadFailure.Duplicate("w1", "This file is already in the library."), classifyUploadError(DuplicateEditionException("e1", "w1"), afterSend = true))
        assertEquals(UploadFailure.RateLimited, classifyUploadError(api(429), afterSend = false))
        assertEquals(UploadFailure.TooLarge, classifyUploadError(api(413), afterSend = true))
        assertEquals(UploadFailure.Unsupported, classifyUploadError(api(415), afterSend = true))
        // A 400 after the whole file arrived is the content check failing...
        assertEquals(UploadFailure.FailedCheck("file contains active content"), classifyUploadError(api(400, "file contains active content"), afterSend = true))
        // ...before that, it's a bad request.
        assertIs<UploadFailure.Other>(classifyUploadError(api(400), afterSend = false))
        assertIs<UploadFailure.Other>(classifyUploadError(RuntimeException("offline"), afterSend = false))
    }

    @Test
    fun suggestEditSummaryAndReviewText() {
        assertEquals("No changes yet", changeSummary(0, 0))
        assertEquals("1 change", changeSummary(1, 0))
        assertEquals("3 changes · fix 1 field to submit", changeSummary(3, 1))
        assertEquals("subtitle and description", joinNatural(listOf("subtitle", "description")))
        assertEquals("a, b and c", joinNatural(listOf("a", "b", "c")))
        assertEquals("10\u00A0Oct,\u00A009:14", submittedAt("2026-10-10T09:14:33Z"))
        assertEquals("", displayValue("publication_year", JsonPrimitive(0)))
        assertEquals("", displayValue("subtitle", JsonNull))
        assertEquals("1871", displayValue("publication_year", JsonPrimitive(1871)))
    }
}
