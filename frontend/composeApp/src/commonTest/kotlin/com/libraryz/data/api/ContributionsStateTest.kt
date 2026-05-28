package com.libraryz.data.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
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

private val twoPendingPayload = """[
    {"id":"c1","work_id":"w1","contributor_id":7,"contributor_name":"Mira",
     "status":"pending","patch":{"title":"New"},"current":{"title":"Old"},
     "created_at":"2026-05-27T00:00:00Z","updated_at":"2026-05-27T00:00:00Z"},
    {"id":"c2","work_id":"w2","contributor_id":8,"contributor_name":"Devon",
     "status":"pending","patch":{"authors":"y"},"current":{"authors":"z"},
     "created_at":"2026-05-27T00:00:00Z","updated_at":"2026-05-27T00:00:00Z"}
]"""

class ContributionsStateTest {

    @Test
    fun refreshLoadsPendingItems() = runTest {
        val engine = MockEngine {
            val (s, body, h) = jsonBody(twoPendingPayload)
            respond(body, s, h)
        }
        val api = ApiClient(BASE, engine = engine)
        val state = ContributionsState(api)

        assertTrue(state.loading, "initial state should be loading")
        state.refresh()

        assertNotNull(state.items)
        assertEquals(2, state.items!!.size)
        assertEquals(2, state.pendingCount)
        assertNull(state.error)
        // The "old" side of the diff decodes alongside the patch.
        val first = state.items!!.first { it.id == "c1" }
        assertEquals("Old", (first.current["title"] as JsonPrimitive).content)
        assertEquals("New", (first.patch["title"] as JsonPrimitive).content)
    }

    @Test
    fun refreshOn500SurfacesError() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError, "boom") }
        val api = ApiClient(BASE, engine = engine)
        val state = ContributionsState(api)
        state.refresh()
        assertNotNull(state.error)
    }

    @Test
    fun approveRemovesItemFromLocalList() = runTest {
        val engine = MockEngine { req ->
            if (req.url.encodedPath.endsWith("/approve")) {
                val (s, body, h) = jsonBody(
                    """{"id":"c1","work_id":"w1","contributor_id":7,
                       "status":"approved",
                       "created_at":"2026-05-27T00:00:00Z",
                       "updated_at":"2026-05-27T00:00:00Z"}""",
                )
                respond(body, s, h)
            } else {
                val (s, body, h) = jsonBody(twoPendingPayload)
                respond(body, s, h)
            }
        }
        val api = ApiClient(BASE, engine = engine)
        val state = ContributionsState(api)
        state.refresh()
        assertEquals(2, state.pendingCount)

        state.approve("c1")

        assertEquals(1, state.pendingCount)
        assertEquals("c2", state.items!![0].id)
    }

    @Test
    fun rejectRemovesItemFromLocalList() = runTest {
        val engine = MockEngine { req ->
            if (req.url.encodedPath.endsWith("/reject")) {
                val (s, body, h) = jsonBody(
                    """{"id":"c2","work_id":"w2","contributor_id":8,
                       "status":"rejected",
                       "created_at":"2026-05-27T00:00:00Z",
                       "updated_at":"2026-05-27T00:00:00Z"}""",
                )
                respond(body, s, h)
            } else {
                val (s, body, h) = jsonBody(twoPendingPayload)
                respond(body, s, h)
            }
        }
        val api = ApiClient(BASE, engine = engine)
        val state = ContributionsState(api)
        state.refresh()
        state.reject("c2")

        assertEquals(1, state.pendingCount)
        assertEquals("c1", state.items!![0].id)
    }

    @Test
    fun approveFailurePropagatesAndDoesNotMutateList() = runTest {
        val engine = MockEngine { req ->
            if (req.url.encodedPath.endsWith("/approve")) {
                respondError(HttpStatusCode.Forbidden, "moderator required")
            } else {
                val (s, body, h) = jsonBody(twoPendingPayload)
                respond(body, s, h)
            }
        }
        val api = ApiClient(BASE, engine = engine)
        val state = ContributionsState(api)
        state.refresh()
        assertEquals(2, state.pendingCount)

        var thrown: Throwable? = null
        try { state.approve("c1") } catch (e: Throwable) { thrown = e }
        assertNotNull(thrown, "approve should rethrow API errors")
        // Local list untouched on failure.
        assertEquals(2, state.pendingCount)
    }
}
