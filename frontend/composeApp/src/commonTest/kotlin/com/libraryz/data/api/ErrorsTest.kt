package com.libraryz.data.api

import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorsTest {
    private val server = "http://192.168.0.99:8080"
    private val unreachable =
        "Can’t reach the library at 192.168.0.99:8080. Check your connection, or the server address in Settings."

    @Test
    fun noAnswerNamesTheServerNotTheException() {
        val timeout = IOException("Connect timeout has expired [url=http://192.168.0.99:8080/me/library, connect_timeout=unknown ms]")
        assertEquals(unreachable, describeError(timeout, server))
        // Wrapped, as engines and plugins sometimes do.
        assertEquals(unreachable, describeError(IllegalStateException("request failed", timeout), server))
        // The browser's fetch.
        assertTrue(isUnreachable(Error("Fail to fetch")))
    }

    @Test
    fun serverAnswersKeepTheirMeaning() {
        assertEquals("title must not be empty", describeError(ApiException(400, "title must not be empty", "PATCH"), server))
        assertEquals(
            "The library ran into a problem. Please try again in a moment.",
            describeError(ApiException(503, "upstream connect error", "GET"), server),
        )
        assertFalse(isUnreachable(ApiException(502, "fetch failed upstream", "GET")))
    }

    @Test
    fun anythingElseIsGeneric() {
        assertEquals("Something went wrong. Please try again.", describeError(IllegalArgumentException("Unexpected JSON token at offset 3"), server))
    }

    @Test
    fun aStateShowsTheSentenceWhenTheServerIsDown() = runTest {
        val engine = MockEngine { throw IOException("Connection refused") }
        val state = LibraryState(ApiClient(server, tokenProvider = { "t" }, engine = engine))
        state.refresh()
        assertEquals(unreachable, state.error)
    }
}
