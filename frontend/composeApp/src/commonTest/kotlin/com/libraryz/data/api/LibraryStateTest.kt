package com.libraryz.data.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BASE = "http://test"

private fun jsonBody(payload: String, status: HttpStatusCode = HttpStatusCode.OK) =
    Triple(
        status,
        payload,
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

private fun entryJson(workId: String, status: String) =
    """{"id":"ub-$workId","user_id":1,"work_id":"$workId","status":"$status",
        "work":{"id":"$workId","title":"Title $workId","editions":[]},
        "created_at":"2026-05-27T00:00:00Z","updated_at":"2026-05-27T00:00:00Z"}"""

class LibraryStateTest {

    @Test
    fun refreshLoadsEntriesAndPopulatesCache() = runTest {
        val engine = MockEngine {
            val (s, body, h) = jsonBody("[${entryJson("w1", "reading")},${entryJson("w2", "want")}]")
            respond(body, s, h)
        }
        val state = LibraryState(ApiClient(BASE, tokenProvider = { "t" }, engine = engine))

        assertTrue(state.loading, "initial state should be loading")
        state.refresh()

        assertNotNull(state.items)
        assertEquals(2, state.items!!.size)
        assertNull(state.error)
        // The list also seeds the per-work cache.
        assertEquals("reading", state.entryFor("w1")?.status)
        assertTrue(state.isLoaded("w1"))
    }

    @Test
    fun refreshForwardsAndRemembersStatusFilter() = runTest {
        var receivedStatus: String? = null
        val engine = MockEngine { req ->
            receivedStatus = req.url.parameters["status"]
            val (s, body, h) = jsonBody("[${entryJson("w1", "reading")}]")
            respond(body, s, h)
        }
        val state = LibraryState(ApiClient(BASE, tokenProvider = { "t" }, engine = engine))
        state.refresh("reading")
        assertEquals("reading", receivedStatus)
        assertEquals("reading", state.statusFilter)
    }

    @Test
    fun refreshOn500SurfacesError() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError, "boom") }
        val state = LibraryState(ApiClient(BASE, tokenProvider = { "t" }, engine = engine))
        state.refresh()
        assertNotNull(state.error)
    }

    @Test
    fun loadEntryCachesNullWhenNotInLibrary() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.NotFound, "nope") }
        val state = LibraryState(ApiClient(BASE, tokenProvider = { "t" }, engine = engine))
        state.loadEntry("w9")
        // Looked up, but not in library: distinct from "never looked up".
        assertTrue(state.isLoaded("w9"))
        assertNull(state.entryFor("w9"))
    }

    @Test
    fun upsertUpdatesCacheAndMergesIntoList() = runTest {
        val engine = MockEngine { req ->
            if (req.method == HttpMethod.Put) {
                val (s, body, h) = jsonBody(entryJson("w1", "read"))
                respond(body, s, h)
            } else {
                val (s, body, h) = jsonBody("[${entryJson("w1", "reading")}]")
                respond(body, s, h)
            }
        }
        val state = LibraryState(ApiClient(BASE, tokenProvider = { "t" }, engine = engine))
        state.refresh() // no filter; w1 is "reading"
        assertEquals("reading", state.entryFor("w1")?.status)

        state.upsert("w1", UpsertLibraryRequest(status = "read"))

        assertEquals("read", state.entryFor("w1")?.status)
        // Replaced in place (no filter active), not duplicated.
        assertEquals(1, state.items!!.size)
        assertEquals("read", state.items!![0].status)
    }

    @Test
    fun upsertDropsEntryFromListWhenItNoLongerMatchesActiveFilter() = runTest {
        val engine = MockEngine { req ->
            if (req.method == HttpMethod.Put) {
                val (s, body, h) = jsonBody(entryJson("w1", "read"))
                respond(body, s, h)
            } else {
                val (s, body, h) = jsonBody("[${entryJson("w1", "reading")}]")
                respond(body, s, h)
            }
        }
        val state = LibraryState(ApiClient(BASE, tokenProvider = { "t" }, engine = engine))
        state.refresh("reading") // filter = reading

        state.upsert("w1", UpsertLibraryRequest(status = "read"))

        // It's now "read", which no longer matches the "reading" filter.
        assertTrue(state.items!!.isEmpty())
        // But the cache still reflects the latest truth.
        assertEquals("read", state.entryFor("w1")?.status)
    }

    @Test
    fun removeClearsCacheAndList() = runTest {
        val engine = MockEngine { req ->
            if (req.method == HttpMethod.Delete) {
                respond("", HttpStatusCode.NoContent)
            } else {
                val (s, body, h) = jsonBody("[${entryJson("w1", "reading")}]")
                respond(body, s, h)
            }
        }
        val state = LibraryState(ApiClient(BASE, tokenProvider = { "t" }, engine = engine))
        state.refresh()
        assertEquals(1, state.items!!.size)

        state.remove("w1")

        assertTrue(state.items!!.isEmpty())
        // Cache now records "looked up, not in library".
        assertTrue(state.isLoaded("w1"))
        assertNull(state.entryFor("w1"))
    }
}
