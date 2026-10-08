package com.libraryz.data.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun jsonRespond(payload: String, status: HttpStatusCode = HttpStatusCode.OK) =
    Triple(
        status,
        payload,
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

class WorksStateTest {

    @Test
    fun initiallyLoadingNoErrorNoItems() {
        val api = ApiClient("http://t", engine = MockEngine { error("not invoked") })
        val state = WorksState(api)
        assertTrue(state.loading)
        assertNull(state.items)
        assertNull(state.error)
    }

    @Test
    fun refreshOnSuccessPopulatesItems() = runTest {
        val engine = MockEngine {
            val (s, body, h) = jsonRespond("""[{"id":"a","title":"Alpha","editions":[]}]""")
            respond(body, s, h)
        }
        val state = WorksState(ApiClient("http://t", engine = engine))

        state.refresh()

        assertNotNull(state.items)
        assertEquals(1, state.items?.size)
        assertEquals("Alpha", state.items?.first()?.title)
        assertNull(state.error)
        assertTrue(!state.loading)
    }

    @Test
    fun refreshOnErrorPopulatesError() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError, "boom") }
        val state = WorksState(ApiClient("http://t", engine = engine))

        state.refresh()

        assertNull(state.items)
        assertNotNull(state.error)
    }

    @Test
    fun refreshOneReplacesEntryInPlace() = runTest {
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/works" -> {
                    val (s, body, h) = jsonRespond(
                        """[{"id":"a","title":"old-A","editions":[]},
                            {"id":"b","title":"B","editions":[]}]""",
                    )
                    respond(body, s, h)
                }
                "/works/a" -> {
                    val (s, body, h) = jsonRespond(
                        """{"id":"a","title":"new-A",
                            "editions":[{"id":"e","work_id":"a","format":"PDF",
                                         "size_bytes":1,"sha256":"x"}]}""",
                    )
                    respond(body, s, h)
                }
                else -> respondError(HttpStatusCode.NotFound, "no")
            }
        }
        val state = WorksState(ApiClient("http://t", engine = engine))
        state.refresh()
        assertEquals("old-A", state.items?.first()?.title)

        state.refreshOne("a")

        val items = state.items!!
        assertEquals("new-A", items.first { it.id == "a" }.title)
        assertEquals(1, items.first { it.id == "a" }.editions.size)
        // 'B' is untouched.
        assertEquals("B", items.first { it.id == "b" }.title)
    }

    @Test
    fun searchHitsSearchEndpointAndExposesQuery() = runTest {
        val engine = MockEngine { req ->
            assertEquals("/works/search", req.url.encodedPath)
            assertEquals("moby", req.url.parameters["q"])
            val (s, body, h) = jsonRespond("""[{"id":"w","title":"Moby Dick","editions":[]}]""")
            respond(body, s, h)
        }
        val state = WorksState(ApiClient("http://t", engine = engine))

        state.search("moby")

        assertEquals("moby", state.searchQuery)
        assertTrue(state.isSearching)
        assertEquals("Moby Dick", state.items?.first()?.title)
    }

    @Test
    fun searchWithBlankQueryFallsBackToRefresh() = runTest {
        var hitPath: String? = null
        val engine = MockEngine { req ->
            hitPath = req.url.encodedPath
            val (s, body, h) = jsonRespond("""[{"id":"w","title":"All","editions":[]}]""")
            respond(body, s, h)
        }
        val state = WorksState(ApiClient("http://t", engine = engine))

        state.search("   ")

        // Blank query should call /works (refresh), not /works/search.
        assertEquals("/works", hitPath)
        assertNull(state.searchQuery)
    }

    @Test
    fun refreshAfterSearchClearsSearchQuery() = runTest {
        val engine = MockEngine { req ->
            val payload = if (req.url.encodedPath == "/works/search")
                """[{"id":"s","title":"Search Hit","editions":[]}]"""
            else
                """[{"id":"w","title":"All","editions":[]}]"""
            val (s, body, h) = jsonRespond(payload)
            respond(body, s, h)
        }
        val state = WorksState(ApiClient("http://t", engine = engine))
        state.search("hit")
        assertNotNull(state.searchQuery)

        state.refresh()

        assertNull(state.searchQuery)
        assertEquals("All", state.items?.first()?.title)
    }

    @Test
    fun findReturnsCachedOrNull() = runTest {
        val engine = MockEngine {
            val (s, body, h) = jsonRespond("""[{"id":"x","title":"X","editions":[]}]""")
            respond(body, s, h)
        }
        val state = WorksState(ApiClient("http://t", engine = engine))
        state.refresh()
        assertEquals("X", state.find("x")?.title)
        assertNull(state.find("missing"))
    }

    @Test
    fun removeWorkDeletesWithReasonAndDropsItFromItems() = runTest {
        val engine = MockEngine { req ->
            when (req.method) {
                io.ktor.http.HttpMethod.Get -> {
                    val (s, body, h) = jsonRespond("""[{"id":"a","title":"Alpha"},{"id":"b","title":"Beta"}]""")
                    respond(body, s, h)
                }
                io.ktor.http.HttpMethod.Delete -> {
                    assertEquals("/works/a", req.url.encodedPath)
                    assertEquals("Bearer tok", req.headers[HttpHeaders.Authorization])
                    assertEquals("""{"reason":"spam"}""", (req.body as io.ktor.http.content.TextContent).text)
                    respond("", HttpStatusCode.NoContent)
                }
                else -> error("unexpected ${req.method}")
            }
        }
        val state = WorksState(ApiClient("http://t", tokenProvider = { "tok" }, engine = engine))
        state.refresh()

        state.removeWork("a", "spam")

        assertEquals(listOf("b"), state.items?.map { it.id })
    }

    @Test
    fun removeEditionDropsOnlyThatEdition() = runTest {
        val engine = MockEngine { req ->
            if (req.method == io.ktor.http.HttpMethod.Delete) {
                assertEquals("/editions/e1", req.url.encodedPath)
                respond("", HttpStatusCode.NoContent)
            } else {
                val (s, body, h) = jsonRespond(
                    """[{"id":"a","title":"Alpha","editions":[
                        {"id":"e1","work_id":"a","format":"pdf","size_bytes":1,"sha256":"x"},
                        {"id":"e2","work_id":"a","format":"txt","size_bytes":1,"sha256":"y"}]}]""",
                )
                respond(body, s, h)
            }
        }
        val state = WorksState(ApiClient("http://t", tokenProvider = { "tok" }, engine = engine))
        state.refresh()

        state.removeEdition("a", "e1", "broken file")

        assertEquals(listOf("e2"), state.find("a")?.editions?.map { it.id })
    }

    @Test
    fun removeWorkFailureThrowsAndKeepsItems() = runTest {
        val engine = MockEngine { req ->
            if (req.method == io.ktor.http.HttpMethod.Delete) {
                respondError(HttpStatusCode.Forbidden, "moderator required")
            } else {
                val (s, body, h) = jsonRespond("""[{"id":"a","title":"Alpha"}]""")
                respond(body, s, h)
            }
        }
        val state = WorksState(ApiClient("http://t", tokenProvider = { "tok" }, engine = engine))
        state.refresh()

        val ex = kotlin.test.assertFailsWith<ApiException> { state.removeWork("a", "x") }
        assertEquals(403, ex.status)
        assertEquals(listOf("a"), state.items?.map { it.id })
    }

    // --- Pagination (#7) ---

    private fun worksJson(ids: IntRange) =
        ids.joinToString(",", "[", "]") { """{"id":"w$it","title":"Work $it"}""" }

    /** Serves `total` works in created order, honouring limit/offset like the backend. */
    private fun pagedEngine(total: Int, path: String = "/works") = MockEngine { req ->
        assertEquals(path, req.url.encodedPath)
        val limit = req.url.parameters["limit"]!!.toInt()
        val offset = req.url.parameters["offset"]!!.toInt()
        val end = minOf(offset + limit, total)
        val (s, body, h) = jsonRespond(if (offset >= end) "[]" else worksJson(offset until end))
        respond(body, s, h)
    }

    @Test
    fun loadMoreAppendsPagesUntilAShortPage() = runTest {
        val engine = pagedEngine(total = 5)
        val state = WorksState(ApiClient("http://t", engine = engine), pageSize = 2)

        state.refresh()
        assertEquals(listOf("w0", "w1"), state.items?.map { it.id })
        assertTrue(!state.endReached)

        state.loadMore()
        assertEquals(listOf("w0", "w1", "w2", "w3"), state.items?.map { it.id })
        assertEquals("2", engine.requestHistory.last().url.parameters["offset"])

        state.loadMore()
        assertEquals(5, state.items?.size)
        assertTrue(state.endReached)

        // At the end: no more requests.
        val requests = engine.requestHistory.size
        state.loadMore()
        assertEquals(requests, engine.requestHistory.size)
    }

    @Test
    fun exactMultipleOfPageSizeEndsOnEmptyPage() = runTest {
        val state = WorksState(ApiClient("http://t", engine = pagedEngine(total = 4)), pageSize = 2)
        state.refresh()
        state.loadMore()
        assertTrue(!state.endReached)
        state.loadMore()
        assertTrue(state.endReached)
        assertEquals(4, state.items?.size)
    }

    @Test
    fun loadMoreDeduplicatesShiftedRows() = runTest {
        // A work inserted at the head between page loads shifts offsets by one,
        // so the next page repeats the previous page's last row.
        var shifted = false
        val engine = MockEngine { req ->
            val offset = req.url.parameters["offset"]!!.toInt()
            val ids = if (!shifted) 0..1 else (offset - 1) until (offset + 1)
            shifted = true
            val (s, body, h) = jsonRespond(worksJson(ids))
            respond(body, s, h)
        }
        val state = WorksState(ApiClient("http://t", engine = engine), pageSize = 2)
        state.refresh()
        state.loadMore()
        assertEquals(listOf("w0", "w1", "w2"), state.items?.map { it.id })
    }

    @Test
    fun searchPaginatesTheSearchEndpoint() = runTest {
        val engine = pagedEngine(total = 3, path = "/works/search")
        val state = WorksState(ApiClient("http://t", engine = engine), pageSize = 2)

        state.search("work")
        state.loadMore()

        assertEquals(3, state.items?.size)
        assertTrue(state.endReached)
        assertTrue(engine.requestHistory.all { it.url.parameters["q"] == "work" })
    }

    @Test
    fun newSearchResetsPagingAndDiscardsStalePages() = runTest {
        val page2Requested = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val engine = MockEngine { req ->
            val offset = req.url.parameters["offset"]!!.toInt()
            if (req.url.encodedPath == "/works" && offset > 0) {
                page2Requested.complete(Unit)
                gate.await() // hold page 2 in flight
            }
            val ids = if (req.url.encodedPath == "/works") offset..offset + 1 else 100..100
            val (s, body, h) = jsonRespond(worksJson(ids))
            respond(body, s, h)
        }
        val state = WorksState(ApiClient("http://t", engine = engine), pageSize = 2)
        state.refresh()

        val pending = async { state.loadMore() }
        page2Requested.await()
        state.search("x") // replaces the list while page 2 is in flight
        gate.complete(Unit)
        pending.await()

        assertEquals(listOf("w100"), state.items?.map { it.id })
        assertTrue(state.endReached)
        assertTrue(!state.loadingMore)
    }

    @Test
    fun loadMoreFailureKeepsItemsAndReportsError() = runTest {
        val engine = MockEngine { req ->
            if (req.url.parameters["offset"] == "0") {
                val (s, body, h) = jsonRespond(worksJson(0..1))
                respond(body, s, h)
            } else {
                respondError(HttpStatusCode.InternalServerError, "boom")
            }
        }
        val state = WorksState(ApiClient("http://t", engine = engine), pageSize = 2)
        state.refresh()
        state.loadMore()

        assertEquals(2, state.items?.size)
        assertNotNull(state.loadMoreError)
        assertNull(state.error)
        assertTrue(!state.loadingMore)
    }
}
