package com.libraryz.data.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
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

    @Test
    fun continueReadingIsTheLatestInProgressBook() = runTest {
        fun e(workId: String, status: String, percent: Int, updated: String) =
            """{"id":"ub-$workId","user_id":1,"work_id":"$workId","status":"$status",
                "progress_percent":$percent,"work":{"id":"$workId","title":"T","editions":[]},
                "created_at":"2026-05-27T00:00:00Z","updated_at":"$updated"}"""
        val engine = MockEngine {
            val (s, body, h) = jsonBody(
                "[" + listOf(
                    e("old", "reading", 30, "2026-05-01T00:00:00Z"),
                    e("new", "reading", 10, "2026-05-20T00:00:00Z"),
                    e("unstarted", "reading", 0, "2026-05-25T00:00:00Z"),
                    e("done", "read", 100, "2026-05-26T00:00:00Z"),
                ).joinToString(",") + "]",
            )
            respond(body, s, h)
        }
        val state = LibraryState(ApiClient(BASE, tokenProvider = { "t" }, engine = engine))
        assertNull(state.continueReading)

        state.refresh()

        // Unstarted and finished books aren't "continue" candidates.
        assertEquals("new", state.continueReading?.workId)
    }
}

class LibraryOfflineTest {

    private class Memory(var value: String? = null) : TokenStore {
        override suspend fun load() = value
        override suspend fun save(token: String) { value = token }
        override suspend fun clear() { value = null }
    }

    private fun state(disk: Memory, online: () -> Boolean, server: String = BASE) = LibraryState(
        ApiClient(
            BASE, tokenProvider = { "t" },
            engine = MockEngine {
                if (!online()) throw kotlinx.io.IOException("connection refused")
                val (s, body, h) = jsonBody("[${entryJson("w1", "reading")},${entryJson("w2", "want")}]")
                respond(body, s, h)
            },
        ),
        storeFactory = { disk },
        server = server,
    )

    @Test
    fun unreachableShowsTheSavedCopy() = runTest {
        val disk = Memory()
        state(disk, online = { true }).refresh()

        // A later start with no server: the saved library, not an error.
        val offline = state(disk, online = { false })
        offline.refresh()
        assertNull(offline.error)
        assertTrue(offline.offline)
        assertEquals(setOf("w1", "w2"), offline.items!!.map { it.workId }.toSet())
        assertEquals("reading", offline.entryFor("w1")?.status)
        // A status tab filters the saved copy.
        offline.refresh("want")
        assertEquals(listOf("w2"), offline.items!!.map { it.workId })
        // Looking one up offline keeps what's saved.
        offline.loadEntry("w1")
        assertEquals("reading", offline.entryFor("w1")?.status)
    }

    @Test
    fun otherServerOrSignOutLeavesNothing() = runTest {
        val disk = Memory()
        state(disk, online = { true }).refresh()

        val elsewhere = state(disk, online = { false }, server = "http://other")
        elsewhere.refresh()
        assertNotNull(elsewhere.error)
        assertTrue(!elsewhere.offline)

        state(disk, online = { true }).forget()
        val after = state(disk, online = { false })
        after.refresh()
        assertNotNull(after.error)
    }
}

class LibraryQueueTest {

    private class Memory(var value: String? = null) : TokenStore {
        override suspend fun load() = value
        override suspend fun save(token: String) { value = token }
        override suspend fun clear() { value = null }
    }

    private class Server {
        var online = true
        val puts = mutableListOf<String>()
        val deletes = mutableListOf<String>()
        var stored = entryJson("w1", "want")
        val engine = MockEngine { req ->
            if (!online) throw kotlinx.io.IOException("connection refused")
            when (req.method) {
                HttpMethod.Put -> {
                    val body = (req.body as io.ktor.http.content.TextContent).text
                    puts += body
                    stored = entryJson("w1", "reading").replace("\"status\":\"reading\"", "\"status\":\"reading\",\"current_page\":42")
                    val (s, b, h) = jsonBody(stored)
                    respond(b, s, h)
                }
                HttpMethod.Delete -> {
                    deletes += req.url.parameters["stamp"].orEmpty()
                    respond("", HttpStatusCode.NoContent)
                }
                else -> {
                    val (s, b, h) = jsonBody("[$stored]")
                    respond(b, s, h)
                }
            }
        }
    }

    private fun state(disk: Memory, server: Server) =
        LibraryState(ApiClient(BASE, tokenProvider = { "t" }, engine = server.engine), storeFactory = { disk }, server = BASE)

    @Test
    fun changesMadeOfflineApplyAtOnceAndAreSentLater() = runTest {
        val disk = Memory()
        val server = Server()
        state(disk, server).refresh()

        // Offline: the change shows at once and waits, even across a restart.
        server.online = false
        val offline = state(disk, server)
        offline.refresh()
        val ub = offline.upsert("w1", UpsertLibraryRequest(currentPage = 42, status = "reading"))
        assertEquals(42, ub.currentPage)
        assertEquals("reading", offline.entryFor("w1")?.status)
        assertEquals(1, offline.pending.size)
        assertTrue(server.puts.isEmpty())

        val later = state(disk, server)
        later.refresh()
        assertEquals(42, later.entryFor("w1")?.currentPage)
        assertEquals(1, later.pending.size)

        // Back online: sent once, with stamps, and the queue empties.
        server.online = true
        later.refresh()
        assertEquals(1, server.puts.size)
        assertTrue("\"stamps\"" in server.puts[0] && "\"progress\"" in server.puts[0], server.puts[0])
        assertTrue(later.pending.isEmpty())
        assertEquals(42, later.entryFor("w1")?.currentPage)
    }

    @Test
    fun anotherUsersQueueIsDropped() = runTest {
        val disk = Memory()
        val server = Server()
        val a = state(disk, server)
        a.signedInAs(1)
        server.online = false
        a.remove("w1")
        assertEquals(1, a.pending.size)

        // Session expired; someone else signs in on this device.
        server.online = true
        val b = state(disk, server)
        b.signingIn()
        b.refresh()
        assertTrue(server.deletes.isEmpty(), "held until we know who signed in")
        b.signedInAs(2)
        assertTrue(server.deletes.isEmpty())
        assertTrue(b.pending.isEmpty())
    }

    @Test
    fun theSameUsersQueueIsSentAfterSigningIn() = runTest {
        val disk = Memory()
        val server = Server()
        val a = state(disk, server)
        a.signedInAs(1)
        server.online = false
        a.remove("w1")

        server.online = true
        val again = state(disk, server)
        again.signingIn()
        again.signedInAs(1)
        assertEquals(1, server.deletes.size)
        assertTrue(server.deletes[0].endsWith(".0000." + server.deletes[0].substringAfterLast('.')))
        assertTrue(again.pending.isEmpty())
    }

    @Test
    fun removalWhileAChangeIsBeingSentIsStillSent() = runTest {
        val disk = Memory()
        val firstPut = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var puts = 0
        val deletes = mutableListOf<String>()
        val engine = MockEngine { req ->
            when (req.method) {
                HttpMethod.Put -> {
                    if (puts++ == 0) {
                        firstPut.complete(Unit)
                        release.await() // still on its way when the next changes are made
                    }
                    val (s, b, h) = jsonBody(entryJson("w1", "reading"))
                    respond(b, s, h)
                }
                HttpMethod.Delete -> {
                    deletes += req.url.encodedPath
                    respond("", HttpStatusCode.NoContent)
                }
                else -> {
                    val (s, b, h) = jsonBody("[]")
                    respond(b, s, h)
                }
            }
        }
        val state = LibraryState(ApiClient(BASE, tokenProvider = { "t" }, engine = engine), storeFactory = { disk }, server = BASE)
        state.signedInAs(1)

        val sending = launch { state.upsert("w1", UpsertLibraryRequest(currentPage = 1, totalPages = 300)) }
        firstPut.await()
        // Another change to the same book, then taking it out, while the first is in flight.
        val more = launch { state.upsert("w1", UpsertLibraryRequest(currentPage = 2, totalPages = 300)) }
        testScheduler.runCurrent()
        val removal = launch { state.remove("w1") }
        testScheduler.runCurrent()
        release.complete(Unit)
        joinAll(sending, more, removal)

        assertEquals(listOf("/me/library/w1"), deletes, "the removal reached the server")
        assertTrue(state.pending.isEmpty())
        assertNull(state.entryFor("w1"))
    }

    @Test
    fun removalAChangeOfAnotherBookIsQueuedBehindKeepsThatChange() = runTest {
        val disk = Memory()
        val firstPut = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val putPaths = mutableListOf<String>()
        val engine = MockEngine { req ->
            when (req.method) {
                HttpMethod.Put -> {
                    putPaths += req.url.encodedPath
                    if (putPaths.size == 1) {
                        firstPut.complete(Unit)
                        release.await()
                    }
                    val id = req.url.encodedPath.substringAfterLast('/')
                    val (s, b, h) = jsonBody(entryJson(id, "reading"))
                    respond(b, s, h)
                }
                HttpMethod.Delete -> respond("", HttpStatusCode.NoContent)
                else -> {
                    val (s, b, h) = jsonBody("[]")
                    respond(b, s, h)
                }
            }
        }
        val state = LibraryState(ApiClient(BASE, tokenProvider = { "t" }, engine = engine), storeFactory = { disk }, server = BASE)
        state.signedInAs(1)

        val jobs = mutableListOf(launch { state.upsert("w1", UpsertLibraryRequest(currentPage = 1)) })
        firstPut.await()
        jobs += launch { state.upsert("w2", UpsertLibraryRequest(status = "want")) }
        testScheduler.runCurrent()
        jobs += launch { state.upsert("w1", UpsertLibraryRequest(currentPage = 2)) }
        testScheduler.runCurrent()
        jobs += launch { state.remove("w1") }
        testScheduler.runCurrent()
        release.complete(Unit)
        jobs.joinAll()

        assertTrue("/me/library/w2" in putPaths, "w2's change was sent: $putPaths")
        assertTrue(state.pending.isEmpty())
    }

    /** A server whose first PUT waits for [release] and whose later answers come from [after]. */
    private class Gated(val after: (Int) -> HttpStatusCode = { HttpStatusCode.OK }) {
        val firstPut = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val puts = mutableListOf<String>()
        val engine = MockEngine { req ->
            when (req.method) {
                HttpMethod.Put -> {
                    puts += req.url.encodedPath
                    val n = puts.size
                    if (n == 1) {
                        firstPut.complete(Unit)
                        release.await()
                    }
                    val status = after(n)
                    if (status != HttpStatusCode.OK) return@MockEngine respondError(status, "busy")
                    val id = req.url.encodedPath.substringAfterLast('/')
                    val (s, b, h) = jsonBody(entryJson(id, "reading"))
                    respond(b, s, h)
                }
                HttpMethod.Delete -> respond("", HttpStatusCode.NoContent)
                else -> {
                    val (s, b, h) = jsonBody("[]")
                    respond(b, s, h)
                }
            }
        }
    }

    @Test
    fun signingOutWhileAChangeIsBeingSentIgnoresItsAnswer() = runTest {
        val disk = Memory()
        val server = Gated()
        val state = LibraryState(ApiClient(BASE, tokenProvider = { "t" }, engine = server.engine), storeFactory = { disk }, server = BASE)
        state.signedInAs(1)
        state.refresh()

        val sending = launch { state.upsert("w1", UpsertLibraryRequest(currentPage = 1)) }
        server.firstPut.await()
        state.forget()
        server.release.complete(Unit)
        sending.join()

        assertNull(state.entryFor("w1"), "the late answer stays out of the cache")
        assertTrue(!state.isLoaded("w1"))
        assertNull(state.items)
        assertTrue(state.pending.isEmpty())
        assertNull(disk.value, "and off the device")
    }

    @Test
    fun cancellingAChangeBeingSentKeepsIt() = runTest {
        val disk = Memory()
        val server = Gated()
        val state = LibraryState(ApiClient(BASE, tokenProvider = { "t" }, engine = server.engine), storeFactory = { disk }, server = BASE)
        state.signedInAs(1)

        val sending = launch { state.upsert("w1", UpsertLibraryRequest(currentPage = 1)) }
        server.firstPut.await()
        sending.cancelAndJoin() // e.g. the reader that saved progress closed

        // Not a refusal: it waits, first in line, on the device too.
        assertEquals(listOf("w1" to 1), state.pending.map { it.workId to it.upsert?.currentPage })
        assertEquals(1, state.entryFor("w1")?.currentPage)
        val next = LibraryState(ApiClient(BASE, tokenProvider = { "t" }, engine = server.engine), storeFactory = { Memory(disk.value) }, server = BASE)
        next.signingIn()
        next.refresh()
        assertEquals(listOf("w1"), next.pending.map { it.workId })

        server.release.complete(Unit)
        state.sync()
        assertTrue(state.pending.isEmpty(), "and goes out with the next sync")
        assertEquals(2, server.puts.size)
    }

    @Test
    fun aChangeThatCantGetThroughStaysAheadOfLaterOnes() = runTest {
        val disk = Memory()
        val server = Gated(after = { HttpStatusCode.ServiceUnavailable })
        val state = LibraryState(ApiClient(BASE, tokenProvider = { "t" }, engine = server.engine), storeFactory = { disk }, server = BASE)
        state.signedInAs(1)

        val jobs = mutableListOf(launch { state.upsert("w1", UpsertLibraryRequest(currentPage = 1)) })
        server.firstPut.await()
        jobs += launch { state.upsert("w2", UpsertLibraryRequest(status = "want")) }
        testScheduler.runCurrent()
        jobs += launch { state.upsert("w1", UpsertLibraryRequest(currentPage = 2)) }
        testScheduler.runCurrent()
        server.release.complete(Unit)
        jobs.joinAll()

        // p. 1 kept as it was and first; p. 2 waits behind w2, not merged into p. 1.
        assertEquals(
            listOf("w1" to 1, "w2" to null, "w1" to 2),
            state.pending.map { it.workId to it.upsert?.currentPage },
        )
    }

    @Test
    fun aRestartWhileAChangeIsBeingSentKeepsIt() = runTest {
        val disk = Memory()
        val server = Gated()
        val state = LibraryState(ApiClient(BASE, tokenProvider = { "t" }, engine = server.engine), storeFactory = { disk }, server = BASE)
        state.signedInAs(1)

        val sending = launch { state.upsert("w1", UpsertLibraryRequest(currentPage = 1)) }
        server.firstPut.await()
        val more = launch { state.upsert("w2", UpsertLibraryRequest(status = "want")) }
        testScheduler.runCurrent()

        // The app dies here; the next start reads what was saved.
        val offline = MockEngine { throw kotlinx.io.IOException("connection refused") }
        val next = LibraryState(ApiClient(BASE, tokenProvider = { "t" }, engine = offline), storeFactory = { Memory(disk.value) }, server = BASE)
        next.refresh()
        assertEquals(listOf("w1", "w2"), next.pending.map { it.workId })
        assertEquals(1, next.entryFor("w1")?.currentPage)

        server.release.complete(Unit)
        joinAll(sending, more)
    }

    @Test
    fun removalTheServerDeclinesShowsTheEntryAgain() = runTest {
        val disk = Memory()
        val engine = MockEngine { req ->
            when (req.method) {
                // Another device changed it after this removal was made: kept.
                HttpMethod.Delete -> {
                    val (s, b, h) = jsonBody(entryJson("w1", "reading"))
                    respond(b, s, h)
                }
                else -> {
                    val (s, b, h) = jsonBody("[${entryJson("w1", "reading")}]")
                    respond(b, s, h)
                }
            }
        }
        val state = LibraryState(ApiClient(BASE, tokenProvider = { "t" }, engine = engine), storeFactory = { disk }, server = BASE)
        state.signedInAs(1)
        state.refresh()

        state.remove("w1")

        assertEquals("reading", state.entryFor("w1")?.status)
        assertEquals(listOf("w1"), state.items?.map { it.workId })
        assertTrue(state.pending.isEmpty())
    }

    @Test
    fun theListShowsChangesStillWaitingToBeSent() = runTest {
        val disk = Memory()
        // The server lists both books but can't take changes right now.
        val engine = MockEngine { req ->
            when (req.method) {
                HttpMethod.Get -> {
                    val (s, b, h) = jsonBody("[${entryJson("w1", "want")},${entryJson("w2", "want")}]")
                    respond(b, s, h)
                }
                else -> respondError(HttpStatusCode.ServiceUnavailable, "busy")
            }
        }
        val state = LibraryState(ApiClient(BASE, tokenProvider = { "t" }, engine = engine), storeFactory = { disk }, server = BASE)
        state.signedInAs(1)
        state.refresh()
        state.remove("w1")
        state.upsert("w2", UpsertLibraryRequest(status = "read"))
        assertEquals(2, state.pending.size)

        state.refresh()
        assertEquals(listOf("w2"), state.items?.map { it.workId }, "w1 is waiting to be removed")
        assertEquals("read", state.items?.single()?.status)

        // The "Want" tab: w2 is "read" on this device now.
        state.refresh("want")
        assertEquals(emptyList(), state.items?.map { it.workId })
    }

    @Test
    fun newerProgressFromAnotherDeviceIsOfferedNotJumpedTo() = runTest {
        val disk = Memory()
        var page = 0
        var stamp = ""
        val engine = MockEngine { req ->
            if (req.method == HttpMethod.Put) {
                val body = (req.body as io.ktor.http.content.TextContent).text
                page = Regex("\"current_page\":(\\d+)").find(body)!!.groupValues[1].toInt()
                stamp = Regex("\"progress\":\"([^\"]+)\"").find(body)!!.groupValues[1]
            }
            val e = entryJson("w1", "reading").replace(
                "\"status\":\"reading\"",
                "\"status\":\"reading\",\"current_page\":$page,\"total_pages\":300,\"progress_stamp\":\"$stamp\"",
            )
            val (s, b, h) = jsonBody(if (req.method == HttpMethod.Put) e else "[$e]")
            respond(b, s, h)
        }
        val state = LibraryState(ApiClient(BASE, tokenProvider = { "t" }, engine = engine), storeFactory = { disk }, server = BASE)
        state.signedInAs(1)
        state.upsert("w1", UpsertLibraryRequest(currentPage = 40, totalPages = 300))
        // Our own progress: nothing to offer.
        assertNull(state.resumePoint("w1").second)

        // Another device reads on to p. 212.
        page = 212
        stamp = "9999999999999.0000.phone"
        state.refresh()
        val (resume, elsewhere) = state.resumePoint("w1")
        assertEquals(40, resume?.currentPage)
        assertEquals(212, elsewhere?.currentPage)
    }
}

