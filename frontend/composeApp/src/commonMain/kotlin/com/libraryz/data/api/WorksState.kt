package com.libraryz.data.api

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.libraryz.data.Work

/**
 * Holds the works list + per-work cache. UI-friendly tri-state:
 *   - items == null && error == null → loading
 *   - error != null → error
 *   - else → loaded (possibly empty)
 *
 * The cache is keyed by Work.id; [refreshOne] fetches the full Work
 * (editions preloaded by the backend) and merges it back into the list.
 */
@Stable
class WorksState(private val api: ApiClient) {
    var items: List<Work>? by mutableStateOf(null)
        private set

    var error: String? by mutableStateOf(null)
        private set

    // When non-null, [items] reflects a search rather than the full list.
    // UI consumers read this both to highlight matches in titles and to
    // distinguish the "empty library" empty state from "no matches".
    var searchQuery: String? by mutableStateOf(null)
        private set

    val loading: Boolean get() = items == null && error == null
    val isSearching: Boolean get() = searchQuery != null

    suspend fun refresh() {
        error = null
        searchQuery = null
        try {
            items = api.listWorks()
        } catch (e: Throwable) {
            error = e.message ?: "Failed to load works"
        }
    }

    suspend fun search(q: String) {
        val trimmed = q.trim()
        if (trimmed.isEmpty()) {
            // Treat empty / whitespace-only as "clear search" to spare the
            // backend a guaranteed 400 and to match the design's "× restores
            // full list" affordance.
            refresh()
            return
        }
        error = null
        searchQuery = trimmed
        try {
            items = api.searchWorks(trimmed)
        } catch (e: Throwable) {
            error = e.message ?: "Search failed"
        }
    }

    suspend fun refreshOne(id: String): Work? {
        return try {
            val w = api.getWork(id)
            items = items?.map { if (it.id == id) w else it } ?: listOf(w)
            w
        } catch (e: Throwable) {
            error = e.message ?: "Failed to load work"
            null
        }
    }

    fun find(id: String): Work? = items?.firstOrNull { it.id == id }
}
