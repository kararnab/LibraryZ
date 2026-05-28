package com.libraryz.data.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BASE = "http://test"

class RecommendationsStateTest {

    private fun engineReturning(body: String) = MockEngine {
        respond(
            body,
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }

    @Test
    fun refreshLoadsRecommendations() = runTest {
        val engine = engineReturning(
            """[{"work":{"id":"w1","title":"Dune","editions":[]},"reason":"Popular on LibraryZ"}]""",
        )
        val state = RecommendationsState(ApiClient(BASE, tokenProvider = { "t" }, engine = engine))

        assertTrue(state.loading, "initial state should be loading")
        state.refresh()

        assertNotNull(state.items)
        assertEquals(1, state.items!!.size)
        assertEquals("Dune", state.items!![0].work.title)
        assertEquals("Popular on LibraryZ", state.items!![0].reason)
        assertNull(state.error)
    }

    @Test
    fun refreshOn500SurfacesError() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError, "boom") }
        val state = RecommendationsState(ApiClient(BASE, tokenProvider = { "t" }, engine = engine))
        state.refresh()
        assertNotNull(state.error)
        assertNull(state.items)
    }

    @Test
    fun dismissRemovesItemLocally() = runTest {
        val engine = MockEngine { req ->
            if (req.url.encodedPath.endsWith("/dismiss")) {
                respond("", HttpStatusCode.NoContent)
            } else {
                respond(
                    """[{"work":{"id":"w1","title":"One","editions":[]},"reason":"x"},
                        {"work":{"id":"w2","title":"Two","editions":[]},"reason":"y"}]""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        }
        val state = RecommendationsState(ApiClient(BASE, tokenProvider = { "t" }, engine = engine))
        state.refresh()
        assertEquals(2, state.items!!.size)

        state.dismiss("w1")

        assertEquals(1, state.items!!.size)
        assertEquals("w2", state.items!![0].work.id)
    }
}
