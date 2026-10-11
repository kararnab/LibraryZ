package com.libraryz.data.api

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.libraryz.data.LibraryStatus
import com.libraryz.data.UserBook
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
 */
@Stable
class LibraryState(
    private val api: ApiClient,
    storeFactory: () -> TokenStore = { NoStore },
    private val server: String = "",
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

    suspend fun refresh(status: String? = statusFilter) {
        error = null
        statusFilter = status
        try {
            val loaded = api.listLibrary(status = status)
            if (status == null) {
                // The full list: anything cached but absent was removed elsewhere.
                cache.keys.filter { k -> loaded.none { it.workId == k } }.forEach { cache[it] = null }
            }
            items = loaded
            loaded.forEach { cache[it.workId] = it }
            offline = false
            save()
        } catch (e: Throwable) {
            val saved = if (isUnreachable(e)) load() else null
            if (saved == null) {
                error = api.describe(e)
            } else {
                saved.forEach { if (cache[it.workId] == null) cache[it.workId] = it }
                items = saved.filter { status == null || it.status == status }
                    .sortedByDescending { it.updatedAt.orEmpty() }
                offline = true
            }
        }
    }

    /** Drops the saved copy and what's in memory (sign-out). */
    suspend fun forget() {
        cache.clear()
        items = null
        offline = false
        runCatching { store.clear() }
    }

    private suspend fun save() {
        val entries = cache.values.filterNotNull()
        runCatching { store.save(json.encodeToString(Snapshot.serializer(), Snapshot(server, entries))) }
    }

    private suspend fun load(): List<UserBook>? = runCatching {
        store.load()?.let { json.decodeFromString(Snapshot.serializer(), it) }
    }.getOrNull()?.takeIf { it.server == server }?.entries

    /** Cached entry for a work, or null if absent / not-in-library. */
    fun entryFor(workId: String): UserBook? = cache[workId]

    /**
     * The book to offer a one-tap resume for: the most recently touched
     * "Reading" entry with some progress. Read from the cache, so it holds
     * regardless of which status filter the My Library list is showing.
     * `updated_at` is RFC 3339 in one server-wide offset, so string order
     * is time order (to the second, which is plenty here).
     */
    val continueReading: UserBook?
        get() = cache.values
            .filterNotNull()
            .filter { it.status == LibraryStatus.Reading && it.progressPercent > 0 }
            .maxByOrNull { it.updatedAt.orEmpty() }

    /** Works known to be in the library (bookmarks in the catalog list). */
    val workIds: Set<String>
        get() = cache.filterValues { it != null }.keys

    /** True once [loadEntry] has run for this work (regardless of result). */
    fun isLoaded(workId: String): Boolean = cache.containsKey(workId)

    suspend fun loadEntry(workId: String) {
        try {
            cache[workId] = api.getLibraryEntry(workId)
        } catch (e: Throwable) {
            // Offline, keep what we know (the saved copy) rather than "not in library".
            if (!isUnreachable(e) || !cache.containsKey(workId)) cache[workId] = null
        }
    }

    suspend fun upsert(workId: String, req: UpsertLibraryRequest): UserBook {
        val updated = api.upsertLibraryEntry(workId, req)
        cache[workId] = updated
        mergeIntoList(updated)
        save()
        return updated
    }

    suspend fun remove(workId: String) {
        api.removeFromLibrary(workId)
        cache[workId] = null
        items = items?.filterNot { it.workId == workId }
        save()
    }

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

@Serializable
private class Snapshot(val server: String, val entries: List<UserBook>)

private val json = Json { ignoreUnknownKeys = true }

private object NoStore : TokenStore {
    override suspend fun load(): String? = null
    override suspend fun save(token: String) = Unit
    override suspend fun clear() = Unit
}
