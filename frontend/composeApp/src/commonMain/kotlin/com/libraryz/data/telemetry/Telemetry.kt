package com.libraryz.data.telemetry

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.libraryz.AppVersion
import com.libraryz.data.api.ApiClient
import com.libraryz.data.api.TokenStore
import com.libraryz.data.api.catchingNonCancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** One usage event; build it with [Events], which the catalog generates. */
class TelemetryEvent(val name: String, val props: Map<String, JsonPrimitive>)

/**
 * What the reader agreed to share. Nothing is sent until they've been
 * [asked], and each kind stays off unless they turned it on. [installId] is
 * a random id for usage events only (to count active installs), dropped
 * when usage sharing is turned off, so turning it back on starts a new one.
 */
@Serializable
data class TelemetryConsent(
    val asked: Boolean = false,
    val crashReports: Boolean = false,
    val usage: Boolean = false,
    val installId: String? = null,
)

/** A small store that writes at once, for a crash that's about to end the process. */
interface SyncStore {
    fun read(): String?
    fun write(value: String)
    fun clear()
}

/** Where the consent choice is kept on this device. */
expect fun createTelemetryStore(): TokenStore

/** Where crash reports wait to be sent (the next launch, for a fatal one); null if nowhere. */
expect fun createCrashStore(): SyncStore?

/** Where usage events wait to be sent, so a session offline or the last minute before closing isn't lost; null if nowhere. */
expect fun createEventStore(): SyncStore?

/** "android", "desktop", "web" or "ios": the server's platform names. */
expect val telemetryPlatform: String

/** The OS family (and Android's major version); nothing finer. */
expect fun telemetryOs(): String

/**
 * Calls [onCrash] with an uncaught exception's type and stack frames (see
 * [exceptionType], [stackFrames]) before the platform's own handling; the
 * process usually ends right after, so [onCrash] must finish synchronously.
 * Installs once per process; a later call only replaces [onCrash].
 */
expect fun installCrashHandler(onCrash: (type: String, frames: List<String>) -> Unit)

@Serializable
data class ClientInfo(val platform: String, val appVersion: String, val os: String)

@Serializable
data class CrashIn(val kind: String, val exception: String, val stack: List<String>, val occurredAt: String)

@Serializable
data class CrashBatch(val client: ClientInfo, val crashes: List<CrashIn>)

@Serializable
data class EventIn(val name: String, val props: Map<String, JsonPrimitive>, val occurredAt: String)

@Serializable
data class EventBatch(val client: ClientInfo, val installId: String, val sessionId: String, val events: List<EventIn>)

/**
 * The app's crash reports and usage events, sent only with consent
 * (docs/observability.md). Crash reports carry the exception's type and
 * its stack frames, never its message; events are the catalog's
 * ([Events]), whose props are enums and flags, never text. Nothing here
 * names the account or what's being read.
 *
 * Both wait on the device (events at most [MAX_QUEUE], oldest dropped
 * first; crash reports at most [MAX_CRASHES], newest kept) until [flush]
 * gets them to the server: about once a minute, and when the app goes to
 * the background or closes ([AppExit]).
 *
 * [track] and [recordCrash] are safe from any thread; [flush] runs one at
 * a time.
 */
@OptIn(ExperimentalUuidApi::class, ExperimentalAtomicApi::class)
class Telemetry(
    private val consentStoreFactory: () -> TokenStore? = { null },
    private val crashStoreFactory: () -> SyncStore? = { null },
    private val eventStoreFactory: () -> SyncStore? = { null },
    private val client: ClientInfo = ClientInfo(telemetryPlatform, AppVersion, telemetryOs()),
    private val now: () -> Instant = { Clock.System.now() },
    private val newId: () -> String = { Uuid.random().toString() },
) {
    private val consentStore by lazy { runCatching(consentStoreFactory).getOrNull() }
    private val crashStore by lazy { runCatching(crashStoreFactory).getOrNull() }
    private val eventStore by lazy { runCatching(eventStoreFactory).getOrNull() }
    private val json = Json { ignoreUnknownKeys = true }

    var consent by mutableStateOf(TelemetryConsent())
        private set

    /** False until [load] has read the saved choice; the app asks only after that. */
    var loaded by mutableStateOf(false)
        private set

    // Read by the crash handler on whatever thread crashed.
    @Volatile private var crashesOn = false
    @Volatile private var usageOn = false

    /** This launch; lets usage be read as sessions. */
    val sessionId: String = newId()

    // Waiting events, oldest first. Replaced whole (never mutated), so a
    // track on the main thread and a flush elsewhere can't tear it.
    private val queue = AtomicReference(emptyList<EventIn>())
    private val sending = Mutex()

    /** Events waiting to be sent (for tests). */
    val pendingEvents: Int get() = queue.load().size

    suspend fun load() {
        val saved = runCatching { consentStore?.load()?.let { json.decodeFromString<TelemetryConsent>(it) } }.getOrNull()
        apply(saved ?: TelemetryConsent())
        // Events saved by an earlier run (offline, or closed before a send).
        if (usageOn) {
            val waiting = readList<EventIn>(eventStore)
            updateQueue { (waiting + it).takeLast(MAX_QUEUE) }
        } else {
            runCatching { eventStore?.clear() }
        }
        loaded = true
    }

    /** Records the reader's choice (from the first-run card or Settings). */
    suspend fun setConsent(crashReports: Boolean, usage: Boolean) {
        val next = TelemetryConsent(
            asked = true,
            crashReports = crashReports,
            usage = usage,
            installId = if (usage) consent.installId ?: newId() else null,
        )
        if (!usage) {
            queue.store(emptyList())
            runCatching { eventStore?.clear() }
        }
        if (!crashReports) runCatching { crashStore?.clear() }
        apply(next)
        runCatching { consentStore?.save(json.encodeToString(TelemetryConsent.serializer(), next)) }
    }

    private fun apply(c: TelemetryConsent) {
        consent = c
        crashesOn = c.crashReports
        usageOn = c.usage
    }

    /** Queues [event] if the reader shares usage; otherwise does nothing. */
    fun track(event: TelemetryEvent) {
        if (!loaded || !usageOn) return
        val e = EventIn(event.name, event.props, now().toString())
        updateQueue { (it + e).takeLast(MAX_QUEUE) }
    }

    /** Applies [f] to the queue and saves the result. */
    private fun updateQueue(f: (List<EventIn>) -> List<EventIn>) {
        while (true) {
            val cur = queue.load()
            val next = f(cur)
            if (queue.compareAndSet(cur, next)) {
                // Withdrawn meanwhile: setConsent already emptied both.
                if (usageOn) saveList(eventStore, next)
                return
            }
        }
    }

    private inline fun <reified T> readList(store: SyncStore?): List<T> =
        store?.let { runCatching { it.read()?.let { t -> json.decodeFromString<List<T>>(t) } }.getOrNull() }.orEmpty()

    private inline fun <reified T> saveList(store: SyncStore?, list: List<T>) {
        runCatching { if (list.isEmpty()) store?.clear() else store?.write(json.encodeToString(list)) }
    }

    /** Keeps a crash report if the reader shares them. Safe on any thread. */
    fun recordCrash(type: String, frames: List<String>, fatal: Boolean) {
        if (!crashesOn) return
        val store = crashStore ?: return
        val report = CrashIn(if (fatal) "fatal" else "nonfatal", type, frames.take(MAX_FRAMES), now().toString())
        saveList(store, (readList<CrashIn>(store) + report).takeLast(MAX_CRASHES))
    }

    /** A failure the app survived but shouldn't have hit (a book that won't open). */
    fun recordNonFatal(e: Throwable) = recordCrash(exceptionType(e), stackFrames(e), fatal = false)

    /**
     * Sends waiting crash reports, then events in batches until none are
     * left. What the server took, or refused (4xx: it never will), is
     * dropped; with no answer, a 429 or a 5xx the rest waits for the next
     * try. One flush at a time (the minute loop and [AppExit] may overlap).
     */
    suspend fun flush(api: ApiClient) = sending.withLock {
        if (crashesOn) {
            val crashes = readList<CrashIn>(crashStore)
            if (crashes.isNotEmpty()) {
                val status = catchingNonCancel { api.sendCrashes(CrashBatch(client, crashes)) }.getOrNull()
                // Keep any report recorded while this one was on its way.
                if (status != null && done(status)) saveList(crashStore, readList<CrashIn>(crashStore).drop(crashes.size))
            }
        }
        while (usageOn) {
            val installId = consent.installId ?: break
            val batch = queue.load().take(MAX_BATCH)
            if (batch.isEmpty()) break
            val status = catchingNonCancel { api.sendEvents(EventBatch(client, installId, sessionId, batch)) }.getOrNull()
            if (status == null || !done(status)) break
            // Only what was sent: the queue may have grown, or been emptied
            // by a withdrawn consent, while it was in flight.
            updateQueue { cur -> if (cur.take(batch.size) == batch) cur.drop(batch.size) else cur }
        }
    }

    /** The server took the batch, or will never take it. */
    private fun done(status: Int) = status in 200..499 && status != 429

    companion object {
        const val MAX_QUEUE = 200
        const val MAX_BATCH = 50
        const val MAX_CRASHES = 5
        const val MAX_FRAMES = 64
    }
}

/**
 * The exception's type and nothing after it: its message is where a title,
 * a path or a URL would be. `toString()` is "<type>: <message>" everywhere.
 */
internal fun exceptionType(e: Throwable): String = e.toString().substringBefore(": ").trim().take(200)

/**
 * [e]'s stack frames ("at …" lines) and, for each cause, "Caused by: <type>",
 * with hosts, emails and home directories taken out. The server scrubs
 * again; this keeps them off the wire in the first place.
 */
internal fun stackFrames(e: Throwable): List<String> =
    e.stackTraceToString().lineSequence()
        .map { it.trim() }
        .mapNotNull { line ->
            when {
                line.startsWith("Caused by:") -> "Caused by: " + line.removePrefix("Caused by:").trim().substringBefore(": ").trim()
                line.startsWith("at ") -> scrubFrame(line)
                else -> null
            }
        }
        .take(Telemetry.MAX_FRAMES)
        .toList()

private val frameScrubbers = listOf(
    Regex("""[A-Za-z][A-Za-z0-9+.-]*://[^/\s)]*""") to "",
    Regex("""\?[^\s):]*""") to "",
    Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""") to "<email>",
    Regex("""(/home|/Users|/data/user/\d+)/[^/\s)]+""") to "~",
    Regex("""(?i)[A-Z]:\\Users\\[^\\\s)]+""") to "~",
)

internal fun scrubFrame(frame: String): String =
    frameScrubbers.fold(frame) { s, (re, with) -> re.replace(s, with) }.take(300)
