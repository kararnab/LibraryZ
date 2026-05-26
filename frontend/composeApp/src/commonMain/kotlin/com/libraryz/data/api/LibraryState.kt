package com.libraryz.data.api

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.libraryz.data.UserBook

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
 */
@Stable
class LibraryState(private val api: ApiClient) {
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
            items = loaded
            loaded.forEach { cache[it.workId] = it }
        } catch (e: Throwable) {
            error = e.message ?: "Failed to load library"
        }
    }

    /** Cached entry for a work, or null if absent / not-in-library. */
    fun entryFor(workId: String): UserBook? = cache[workId]

    /** True once [loadEntry] has run for this work (regardless of result). */
    fun isLoaded(workId: String): Boolean = cache.containsKey(workId)

    suspend fun loadEntry(workId: String) {
        cache[workId] = runCatching { api.getLibraryEntry(workId) }.getOrNull()
    }

    suspend fun upsert(workId: String, req: UpsertLibraryRequest): UserBook {
        val updated = api.upsertLibraryEntry(workId, req)
        cache[workId] = updated
        mergeIntoList(updated)
        return updated
    }

    suspend fun remove(workId: String) {
        api.removeFromLibrary(workId)
        cache[workId] = null
        items = items?.filterNot { it.workId == workId }
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
