package com.libraryz.data.api

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.libraryz.data.LibraryStatus
import com.libraryz.data.Hlc
import com.libraryz.data.PendingWrite
import com.libraryz.data.UserBook
import com.libraryz.data.Work
import com.libraryz.data.applyLocally
import com.libraryz.data.enqueue
import com.libraryz.data.stampRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Holds the user's personal library. Same tri-state shape as [WorksState] /
 * [ContributionsState]:
 *   - items == null && error == null → loading
 *   - error != null → error
 *   - else → loaded (possibly empty)
 *
 * A separate per-work [cache] backs the WorkDetail controls: the detail screen
 * asks for one work's entry without needing the whole list loaded, and
 * [upsert]/[remove] keep both the cache and (when present) the list in sync so
 * the My Library screen doesn't need a full refresh after an edit.
 *
 * Offline: every entry seen is also saved on the device ([store], for the
 * server at [server]). When the server can't be reached, [refresh] shows
 * that copy instead of an error and sets [offline]; resume positions and
 * "Continue reading" come from it too. [forget] drops it at sign-out.
 *
 * Changes ([upsert], [remove]) apply on the device at once and wait in a
 * saved queue until [sync] delivers them, online or not, so one code path
 * serves both. Each is stamped by an [Hlc] when made and merges per field
 * group on the server (see LibraryMerge.kt). The queue belongs to the user
 * who made it: [signingIn] holds it, and [signedInAs] sends it only if the
 * same user signed in, else drops it.
 */
@Stable
class LibraryState(
    private val api: ApiClient,
    storeFactory: () -> TokenStore = { NoStore },
    private val server: String = "",
    private val now: () -> Instant = { Clock.System.now() },
) {
    // Opened on first use, so building the app doesn't touch platform storage.
    private val store: TokenStore by lazy { runCatching(storeFactory).getOrElse { NoStore } }

    /** True while [items] is the device's saved copy because the server can't be reached. */
    var offline: Boolean by mutableStateOf(false)
        private set

    var items: List<UserBook>? by mutableStateOf(null)
        private set

    var error: String? by mutableStateOf(null)
        private set

    // The status filter the current [items] reflect (null = all).
    var statusFilter: String? by mutableStateOf(null)
        private set

    val loading: Boolean get() = items == null && error == null

    // Per-work cache keyed by workId. A present key with a null value means
    // "looked up, not in library" — distinct from "not yet looked up" (absent).
    private val cache = mutableStateMapOf<String, UserBook?>()

    /** Changes made on this device that the server hasn't confirmed yet, in sending order. */
    val pending: List<PendingWrite> get() = listOfNotNull(inFlight) + queued

    // The change [sync] is sending, set apart so nothing queued meanwhile can
    // merge into it or drop it; [forget] clears it, and its answer is then ignored.
    private var inFlight: PendingWrite? by mutableStateOf(null)
    private var queued: List<PendingWrite> by mutableStateOf(emptyList())

    /** Finds a work for an entry made offline (the list shows its title). */
    var workLookup: (String) -> Work? = { null }

    private var owner: Long? = null
    private var device: String? = null
    // Where this device last was in each book (work id → entry's progress fields).
    private var mine: Map<String, Position> = emptyMap()
    private var clock: Hlc? = null
    private var restored = false
    private var paused = false
    private val lock = Mutex()
    private val sendLock = Mutex()

    suspend fun refresh(status: String? = statusFilter) {
        error = null
        statusFilter = status
        restore()
        // Send what's waiting first, so the list includes it.
        catchingNonCancel { sync() }
        try {
            val loaded = api.listLibrary(status = status)
            loaded.firstOrNull()?.let { owner = owner ?: it.userId }
            if (status == null) {
                // The full list: anything cached but absent was removed elsewhere.
                cache.keys.filter { k -> loaded.none { it.workId == k } }.forEach { cache[it] = null }
            }
            loaded.forEach {
                observe(it)
                cache[it.workId] = withPending(it.workId, it)
            }
            // As this device has it: queued removals stay out, and a queued
            // status change can move an entry off this tab.
            items = loaded.mapNotNull { cache[it.workId] }.filter { status == null || it.status == status } +
                pendingNew(status, loaded)
            offline = false
            save()
        } catch (e: Throwable) {
            e.rethrowIfCancelled()
            val saved = if (isUnreachable(e)) load()?.entries else null
            if (saved == null) {
                error = api.describe(e)
            } else {
                saved.forEach { if (cache[it.workId] == null) cache[it.workId] = it }
                items = cache.values.filterNotNull().filter { status == null || it.status == status }.newestFirst()
                offline = true
            }
        }
    }

    // Entries created on this device and not sent yet, which the server's list lacks.
    private fun pendingNew(status: String?, loaded: List<UserBook>): List<UserBook> =
        pending.map { it.workId }.distinct()
            .filter { id -> loaded.none { it.workId == id } }
            .mapNotNull { cache[it] }
            .filter { status == null || it.status == status }

    /** Drops the saved copy, the queue and what's in memory (sign-out). */
    suspend fun forget() {
        lock.withLock {
            cache.clear()
            items = null
            offline = false
            inFlight = null
            queued = emptyList()
            mine = emptyMap()
            owner = null
            restored = true
            runCatching { store.clear() }
        }
    }

    /** Someone is signing in: hold the queue until [signedInAs] says who. */
    fun signingIn() {
        paused = true
    }

    /**
     * The signed-in user is [userId]. A queue someone else made is dropped,
     * with the saved library; otherwise sending resumes.
     */
    suspend fun signedInAs(userId: Long) {
        restore()
        if (owner != null && owner != userId) forget()
        owner = userId
        paused = false
        catchingNonCancel { sync() }
    }

    private suspend fun save() {
        val entries = cache.values.filterNotNull()
        val snapshot = Snapshot(server, entries, pending, device, owner, mine)
        runCatching { store.save(json.encodeToString(Snapshot.serializer(), snapshot)) }
    }

    private suspend fun load(): Snapshot? = runCatching {
        store.load()?.let { json.decodeFromString(Snapshot.serializer(), it) }
    }.getOrNull()?.takeIf { it.server == server }

    // Reads the queue, device id and owner once per run.
    private suspend fun restore() {
        if (restored) return
        restored = true
        val snapshot = load()
        if (snapshot != null) {
            queued = snapshot.pending
            owner = snapshot.owner
            device = snapshot.device
            mine = snapshot.mine
            snapshot.entries.forEach { if (!cache.containsKey(it.workId)) cache[it.workId] = it }
        }
    }

    private fun clock(): Hlc = clock ?: Hlc(device ?: Hlc.newDevice().also { device = it }) { now().toEpochMilliseconds() }
        .also { c -> clock = c; cache.values.filterNotNull().forEach { observe(it, c) } }

    private fun observe(ub: UserBook, c: Hlc? = clock) {
        c ?: return
        listOf(ub.statusStamp, ub.progressStamp, ub.ratingStamp, ub.shelfStamp, ub.notesStamp).forEach(c::observe)
    }

    /** [server] with this device's unsent changes to [workId] on top. */
    private fun withPending(workId: String, server: UserBook?): UserBook? =
        pending.filter { it.workId == workId }.fold(server) { ub, w ->
            if (w.upsert != null) applyLocally(ub, workId, w.upsert, now().toString(), workLookup(workId), owner ?: 0) else null
        }

    /** Cached entry for a work, or null if absent / not-in-library. */
    fun entryFor(workId: String): UserBook? = cache[workId]

    /**
     * Where to open [workId] and, when another device has read on since this
     * one last did, that device's spot to offer: (resume from, elsewhere).
     */
    fun resumePoint(workId: String): Pair<UserBook?, UserBook?> {
        val entry = cache[workId] ?: return null to null
        val own = mine[workId]
        val fromElsewhere = own != null && Hlc.deviceOf(entry.progressStamp).let { it != null && it != device } &&
            (own.currentPage != entry.currentPage || own.progressPercent != entry.progressPercent)
        if (!fromElsewhere) return entry to null
        return entry.copy(currentPage = own!!.currentPage, totalPages = own.totalPages, progressPercent = own.progressPercent) to entry
    }

    /**
     * The book to offer a one-tap resume for: the most recently touched
     * "Reading" entry with some progress. Read from the cache, so it holds
     * regardless of which status filter the My Library list is showing.
     */
    val continueReading: UserBook?
        get() = cache.values
            .filterNotNull()
            .filter { it.status == LibraryStatus.Reading && it.progressPercent > 0 }
            .newestFirst()
            .firstOrNull()

    /** Works known to be in the library (bookmarks in the catalog list). */
    val workIds: Set<String>
        get() = cache.filterValues { it != null }.keys

    /** True once [loadEntry] has run for this work (regardless of result). */
    fun isLoaded(workId: String): Boolean = cache.containsKey(workId)

    suspend fun loadEntry(workId: String) {
        restore()
        try {
            val ub = api.getLibraryEntry(workId)
            ub?.let { observe(it) }
            cache[workId] = withPending(workId, ub)
        } catch (e: Throwable) {
            e.rethrowIfCancelled()
            // Offline, keep what we know (the saved copy) rather than "not in library".
            if (!isUnreachable(e) || !cache.containsKey(workId)) cache[workId] = null
        }
    }

    /**
     * Changes [workId]'s entry: at once on the device, then on the server
     * when it can be reached. Throws only if the server refused the change
     * (it's then dropped); offline it just waits.
     */
    suspend fun upsert(workId: String, req: UpsertLibraryRequest): UserBook {
        restore()
        val updated = lock.withLock {
            val stamped = stampRequest(req, cache[workId], clock())
            if (req.currentPage != null || req.progressPercent != null) {
                mine = mine + (workId to Position(req.currentPage ?: 0, req.totalPages ?: 0, req.progressPercent ?: 0))
            }
            val ub = applyLocally(cache[workId], workId, stamped, now().toString(), workLookup(workId), owner ?: 0)
            cache[workId] = ub
            mergeIntoList(ub)
            queue(PendingWrite(workId, upsert = stamped))
            save()
            ub
        }
        sync()
        return cache[workId] ?: updated
    }

    /** Takes [workId] out of the library: at once on the device, then on the server. */
    suspend fun remove(workId: String) {
        restore()
        lock.withLock {
            val at = clock().next()
            cache[workId] = null
            items = items?.filterNot { it.workId == workId }
            queue(PendingWrite(workId, removedAt = at))
            save()
        }
        sync()
    }

    private fun queue(w: PendingWrite) {
        queued = enqueue(queued, w)
    }

    /**
     * Sends the queued changes in order. Stops at the first that can't get
     * through for now (no connection, signed out, server trouble), keeping
     * it and the rest. A change the server refuses is dropped, the entry
     * reloaded, and the refusal thrown.
     */
    suspend fun sync() {
        restore()
        if (paused) return
        sendLock.withLock {
            while (true) {
                val w = lock.withLock {
                    queued.firstOrNull()?.also {
                        inFlight = it
                        queued = queued.drop(1)
                    }
                } ?: return
                val result = try {
                    // A removal answers with the entry when the server kept it (changed since elsewhere).
                    if (w.upsert != null) api.upsertLibraryEntry(w.workId, w.upsert) else api.removeFromLibrary(w.workId, w.removedAt)
                } catch (e: Throwable) {
                    // Cancelled mid-send: the change goes back first, like a lost
                    // connection, and the cancellation carries on up.
                    val cancelled = e is CancellationException
                    val ours = withContext(NonCancellable) {
                        lock.withLock {
                            if (inFlight !== w) return@withLock false // signed out meanwhile
                            inFlight = null
                            if (cancelled || isTransient(e)) queued = listOf(w) + queued
                            save()
                            true
                        }
                    }
                    e.rethrowIfCancelled()
                    if (!ours || isTransient(e)) return
                    loadEntry(w.workId)
                    mergeLoaded(w.workId)
                    throw e
                }
                // The server has it: record that even if cancelled now, or the
                // change would stay in flight for good.
                val ours = withContext(NonCancellable) {
                    lock.withLock {
                        if (inFlight !== w) return@withLock false // signed out meanwhile
                        inFlight = null
                        result?.let { observe(it) }
                        val ub = withPending(w.workId, result)
                        cache[w.workId] = ub
                        if (ub != null) mergeIntoList(ub) else items = items?.filterNot { it.workId == w.workId }
                        save()
                        true
                    }
                }
                if (!ours) return
                currentCoroutineContext().ensureActive()
            }
        }
    }

    private fun mergeLoaded(workId: String) {
        val ub = cache[workId]
        if (ub != null) mergeIntoList(ub) else items = items?.filterNot { it.workId == workId }
    }

    private fun isTransient(e: Throwable): Boolean =
        isUnreachable(e) || (e is ApiException && (e.status == 401 || e.status == 408 || e.status == 429 || e.status >= 500))

    private fun mergeIntoList(entry: UserBook) {
        val current = items ?: return
        items = if (current.any { it.workId == entry.workId }) {
            // If a status filter is active and the entry no longer matches,
            // drop it from the visible list; otherwise replace in place.
            if (statusFilter != null && entry.status != statusFilter) {
                current.filterNot { it.workId == entry.workId }
            } else {
                current.map { if (it.workId == entry.workId) entry else it }
            }
        } else if (statusFilter == null || entry.status == statusFilter) {
            listOf(entry) + current
        } else {
            current
        }
    }
}

/** Most recently updated first. Times may carry different offsets (server, device), so they're compared as instants. */
private fun List<UserBook>.newestFirst(): List<UserBook> =
    sortedByDescending { ub -> ub.updatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.DISTANT_PAST }

@Serializable
private class Position(val currentPage: Int, val totalPages: Int, val progressPercent: Int)

@Serializable
private class Snapshot(
    val server: String,
    val entries: List<UserBook>,
    val pending: List<PendingWrite> = emptyList(),
    val device: String? = null,
    val owner: Long? = null,
    val mine: Map<String, Position> = emptyMap(),
)

private val json = Json { ignoreUnknownKeys = true }

private object NoStore : TokenStore {
    override suspend fun load(): String? = null
    override suspend fun save(token: String) = Unit
    override suspend fun clear() = Unit
}
