package com.libraryz.data.api

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.libraryz.data.Work
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Holds the works list + per-work cache. UI-friendly tri-state:
 *   - items == null && error == null → loading
 *   - error != null → error
 *   - else → loaded (possibly empty)
 *
 * The cache is keyed by Work.id; [refreshOne] fetches the full Work
 * (editions preloaded by the backend) and merges it back into the list.
 * It also keeps that Work for [find], so an open book survives a search
 * whose results leave it out.
 *
 * Paged: [refresh] / [search] load the first [pageSize] works and [loadMore]
 * appends the next page (offset = items loaded so far) until a short page
 * marks [endReached]. Offset paging can shift if works are added while the
 * user scrolls, so appended pages are de-duplicated by id.
 */
@Stable
class WorksState(private val api: ApiClient, private val pageSize: Int = DEFAULT_PAGE_SIZE) {
    var items: List<Work>? by mutableStateOf(null)
        private set

    var error: String? by mutableStateOf(null)
        private set

    // When non-null, [items] reflects a search rather than the full list.
    // UI consumers read this both to highlight matches in titles and to
    // distinguish the "empty library" empty state from "no matches".
    var searchQuery: String? by mutableStateOf(null)
        private set

    /** True once the last page has been loaded — no more [loadMore] calls needed. */
    var endReached: Boolean by mutableStateOf(false)
        private set

    var loadingMore: Boolean by mutableStateOf(false)
        private set

    /** Set when a [loadMore] failed; the already-loaded items stay. */
    var loadMoreError: String? by mutableStateOf(null)
        private set

    // Bumped whenever the list is replaced (refresh / new search) so a
    // response still in flight for the old list (a first page or a
    // loadMore) is discarded instead of overwriting the newer one.
    private var generation = 0

    // True while a refresh / search waits for its first page. The old list
    // is still on screen then (and endReached was reset), so the list asks
    // for more; that must not page the new query onto the old items.
    private var replacing = false

    // Works fetched one by one ([refreshOne]), whatever list is showing.
    private val opened = mutableStateMapOf<String, Work>()

    val loading: Boolean get() = items == null && error == null
    val isSearching: Boolean get() = searchQuery != null

    private suspend fun fetchPage(query: String?, offset: Int): List<Work> =
        if (query == null) api.listWorks(limit = pageSize, offset = offset)
        else api.searchWorks(query, limit = pageSize, offset = offset)

    private fun resetPaging() {
        generation++
        endReached = false
        loadingMore = false
        loadMoreError = null
    }

    suspend fun refresh() {
        error = null
        searchQuery = null
        resetPaging()
        val gen = generation
        replacing = true
        try {
            // Not cancellable: the query is already switched, so leaving now
            // would show the old list under it. A newer call discards this one.
            val page = withContext(NonCancellable) { fetchPage(null, 0) }
            if (gen != generation) return // a newer refresh or search replaced it
            items = page
            endReached = page.size < pageSize
        } catch (e: Throwable) {
            if (gen == generation) error = e.message ?: "Failed to load works"
        } finally {
            if (gen == generation) replacing = false
        }
    }

    /** Appends the next page of the current list or search. No-op while one is loading, a new list is loading, or at the end. */
    suspend fun loadMore() {
        val current = items ?: return
        if (loadingMore || endReached || replacing) return
        val gen = generation
        val query = searchQuery
        loadingMore = true
        loadMoreError = null
        try {
            val page = fetchPage(query, current.size)
            if (gen != generation) return // list was replaced meanwhile
            val seen = current.mapTo(HashSet()) { it.id }
            items = current + page.filter { it.id !in seen }
            endReached = page.size < pageSize
        } catch (e: Throwable) {
            if (gen == generation) loadMoreError = e.message ?: "Failed to load more works"
        } finally {
            if (gen == generation) loadingMore = false
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
        resetPaging()
        val gen = generation
        replacing = true
        try {
            // Not cancellable: the query is already switched, so leaving now
            // would show the old list under it. A newer call discards this one.
            val page = withContext(NonCancellable) { fetchPage(trimmed, 0) }
            if (gen != generation) return // a newer refresh or search replaced it
            items = page
            endReached = page.size < pageSize
        } catch (e: Throwable) {
            if (gen == generation) error = e.message ?: "Search failed"
        } finally {
            if (gen == generation) replacing = false
        }
    }

    suspend fun refreshOne(id: String): Work? {
        return try {
            val w = api.getWork(id)
            opened[id] = w
            items = items?.map { if (it.id == id) w else it } ?: listOf(w)
            w
        } catch (e: Throwable) {
            error = e.message ?: "Failed to load work"
            null
        }
    }

    /** Moderator takedown; drops the work from the local list on success. Throws on failure. */
    suspend fun removeWork(id: String, reason: String) {
        api.deleteWork(id, reason)
        opened.remove(id)
        items = items?.filterNot { it.id == id }
    }

    /** Moderator takedown of one edition; updates the cached work on success. Throws on failure. */
    suspend fun removeEdition(workId: String, editionId: String, reason: String) {
        api.deleteEdition(editionId, reason)
        val drop = { w: Work -> w.copy(editions = w.editions.filterNot { it.id == editionId }) }
        opened[workId]?.let { opened[workId] = drop(it) }
        items = items?.map { w -> if (w.id == workId) drop(w) else w }
    }

    fun find(id: String): Work? = items?.firstOrNull { it.id == id } ?: opened[id]
}

const val DEFAULT_PAGE_SIZE = 50
