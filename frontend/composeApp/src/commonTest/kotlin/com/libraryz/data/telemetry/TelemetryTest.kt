package com.libraryz.data.telemetry

import com.libraryz.data.api.ApiClient
import com.libraryz.data.api.FakeTokenStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private class FakeSyncStore(var value: String? = null) : SyncStore {
    override fun read() = value
    override fun write(value: String) {
        this.value = value
    }
    override fun clear() {
        value = null
    }
}

class TelemetryTest {
    private val client = ClientInfo("desktop", "0.3.0", "Linux")
    private var ids = 0

    private fun telemetry(
        consent: FakeTokenStore = FakeTokenStore(),
        crashes: FakeSyncStore = FakeSyncStore(),
        events: FakeSyncStore = FakeSyncStore(),
    ) = Telemetry({ consent }, { crashes }, { events }, client, now = { Instant.parse("2026-10-13T10:00:00Z") }, newId = { "id-${++ids}" })

    private class Server(var status: HttpStatusCode = HttpStatusCode.Accepted, var down: Boolean = false) {
        val requests = mutableListOf<HttpRequestData>()
        val api = ApiClient("http://test", tokenProvider = { "access-token" }, engine = MockEngine { req ->
            if (down) throw kotlinx.io.IOException("connection refused")
            requests += req
            respond("""{"accepted":1,"rejected":0}""", status)
        })
        fun body(i: Int): JsonObject = Json.parseToJsonElement((requests[i].body as TextContent).text).jsonObject
    }

    @Test
    fun nothingIsKeptOrSentWithoutConsent() = runTest {
        val crashes = FakeSyncStore()
        val t = telemetry(crashes = crashes)
        t.load()
        assertFalse(t.consent.asked)
        t.track(Events.appOpened())
        t.recordCrash("E", listOf("at x"), fatal = true)
        assertEquals(0, t.pendingEvents)
        assertNull(crashes.value)
        val server = Server()
        t.flush(server.api)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun eventsGoAnonymouslyWithTheirContext() = runTest {
        val t = telemetry()
        t.load()
        t.setConsent(crashReports = false, usage = true)
        t.track(Events.bookOpened(BookOpenedFormat.Epub, BookOpenedSource.Device))
        t.track(Events.search(hadResults = false))
        val server = Server()
        t.flush(server.api)

        val req = server.requests.single()
        assertEquals("/telemetry/events", req.url.encodedPath)
        assertNull(req.headers[HttpHeaders.Authorization], "telemetry must never carry the session")
        val body = server.body(0)
        assertEquals(t.consent.installId, body["install_id"]?.jsonPrimitive?.content)
        assertEquals(t.sessionId, body["session_id"]?.jsonPrimitive?.content)
        assertEquals("desktop", body["client"]!!.jsonObject["platform"]!!.jsonPrimitive.content)
        assertEquals("0.3.0", body["client"]!!.jsonObject["app_version"]!!.jsonPrimitive.content)
        val events = body["events"]!!.jsonArray
        assertEquals("book_opened", events[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("epub", events[0].jsonObject["props"]!!.jsonObject["format"]!!.jsonPrimitive.content)
        assertEquals("false", events[1].jsonObject["props"]!!.jsonObject["had_results"]!!.jsonPrimitive.content)
        assertEquals("2026-10-13T10:00:00Z", events[0].jsonObject["occurred_at"]!!.jsonPrimitive.content)
        assertEquals(0, t.pendingEvents)
    }

    @Test
    fun turningUsageOffDropsTheQueueAndTheInstallId() = runTest {
        val t = telemetry()
        t.load()
        t.setConsent(crashReports = false, usage = true)
        val first = assertNotNull(t.consent.installId)
        t.track(Events.appOpened())
        t.setConsent(crashReports = false, usage = false)
        assertEquals(0, t.pendingEvents)
        assertNull(t.consent.installId)
        t.setConsent(crashReports = false, usage = true)
        assertNotEquals(first, t.consent.installId, "a new install id after opting back in")
    }

    @Test
    fun theChoiceIsRemembered() = runTest {
        val store = FakeTokenStore()
        telemetry(consent = store).apply { load(); setConsent(crashReports = true, usage = false) }
        val again = telemetry(consent = store)
        again.load()
        assertTrue(again.consent.asked)
        assertTrue(again.consent.crashReports)
        assertFalse(again.consent.usage)
    }

    @Test
    fun eventsWaitWhenTheServerCantTakeThemAndDropWhenRefused() = runTest {
        val t = telemetry()
        t.load()
        t.setConsent(crashReports = false, usage = true)
        t.track(Events.appOpened())
        val server = Server(HttpStatusCode.ServiceUnavailable)
        t.flush(server.api)
        assertEquals(1, t.pendingEvents)
        server.status = HttpStatusCode.TooManyRequests
        t.flush(server.api)
        assertEquals(1, t.pendingEvents)
        // A 400 means the server will never take them: don't retry forever.
        server.status = HttpStatusCode.BadRequest
        t.flush(server.api)
        assertEquals(0, t.pendingEvents)
    }

    @Test
    fun eventsWaitingOfflineSurviveARestart() = runTest {
        val consent = FakeTokenStore()
        val events = FakeSyncStore()
        val before = telemetry(consent, events = events)
        before.load()
        before.setConsent(crashReports = false, usage = true)
        before.track(Events.wentOffline())
        before.track(Events.search(hadResults = true))
        before.flush(Server(down = true).api)
        assertEquals(2, before.pendingEvents)

        // The app closes offline; the next launch sends them.
        val next = telemetry(consent, events = events)
        next.load()
        assertEquals(2, next.pendingEvents)
        val server = Server()
        next.flush(server.api)
        assertEquals(listOf("went_offline", "search"), server.body(0)["events"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals(0, next.pendingEvents)
        assertNull(events.value, "nothing left on the device")
    }

    @Test
    fun aBacklogGoesOutInOneFlush() = runTest {
        val t = telemetry()
        t.load()
        t.setConsent(crashReports = false, usage = true)
        repeat(Telemetry.MAX_BATCH * 2 + 10) { t.track(Events.appOpened()) }
        val server = Server()
        t.flush(server.api)
        assertEquals(3, server.requests.size)
        assertEquals(0, t.pendingEvents)
    }

    @Test
    fun theQueueKeepsTheNewest() = runTest {
        val t = telemetry()
        t.load()
        t.setConsent(crashReports = false, usage = true)
        repeat(Telemetry.MAX_QUEUE) { t.track(Events.appOpened()) }
        t.track(Events.wentOffline())
        assertEquals(Telemetry.MAX_QUEUE, t.pendingEvents)
        val server = Server()
        t.flush(server.api)
        val last = server.body(server.requests.size - 1)["events"]!!.jsonArray.last()
        assertEquals("went_offline", last.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun withdrawingUsageDeletesSavedEvents() = runTest {
        val consent = FakeTokenStore()
        val events = FakeSyncStore()
        val t = telemetry(consent, events = events)
        t.load()
        t.setConsent(crashReports = false, usage = true)
        t.track(Events.appOpened())
        assertNotNull(events.value)
        t.setConsent(crashReports = false, usage = false)
        assertNull(events.value)
        // And events left by a run that crashed before saving the choice go too.
        events.value = """[{"name":"app_opened","props":{},"occurredAt":"2026-10-13T10:00:00Z"}]"""
        val next = telemetry(consent, events = events)
        next.load()
        assertEquals(0, next.pendingEvents)
        assertNull(events.value)
    }

    @Test
    fun aCrashIsKeptAndSentOnTheNextLaunch() = runTest {
        val consent = FakeTokenStore()
        val crashes = FakeSyncStore()
        val before = telemetry(consent, crashes)
        before.load()
        before.setConsent(crashReports = true, usage = false)
        before.recordCrash("java.lang.IllegalStateException", listOf("at com.libraryz.A.b(A.kt:1)"), fatal = true)
        assertNotNull(crashes.value)

        val next = telemetry(consent, crashes)
        next.load()
        val server = Server()
        next.flush(server.api)
        assertEquals("/telemetry/crashes", server.requests.single().url.encodedPath)
        val crash = server.body(0)["crashes"]!!.jsonArray.single().jsonObject
        assertEquals("fatal", crash["kind"]!!.jsonPrimitive.content)
        assertEquals("java.lang.IllegalStateException", crash["exception"]!!.jsonPrimitive.content)
        assertNull(crashes.value)
    }

    @Test
    fun turningCrashReportsOffDeletesWaitingOnes() = runTest {
        val crashes = FakeSyncStore()
        val t = telemetry(crashes = crashes)
        t.load()
        t.setConsent(crashReports = true, usage = false)
        t.recordNonFatal(IllegalStateException("can't open Emma"))
        assertNotNull(crashes.value)
        t.setConsent(crashReports = false, usage = false)
        assertNull(crashes.value)
    }

    @Test
    fun crashReportsCarryNoMessage() {
        val e = RuntimeException("reader@example.com opened “Emma”", IllegalArgumentException("/home/arnab/Emma.epub"))
        assertEquals("RuntimeException", exceptionType(e).substringAfterLast('.'))
        val frames = stackFrames(e)
        assertTrue(frames.isNotEmpty())
        assertTrue(frames.all { it.startsWith("at ") || it.startsWith("Caused by: ") }, frames.toString())
        for (leak in listOf("Emma", "example.com", "arnab")) {
            assertTrue(frames.none { leak in it }, "frames leak $leak: $frames")
        }
        assertEquals("at app (/composeApp.wasm:1:2)", scrubFrame("at app (http://192.168.1.4:8081/composeApp.wasm?t=x:1:2)"))
        assertEquals("at f (~/x.kt:3)", scrubFrame("at f (/home/arnab/x.kt:3)"))
    }
}
