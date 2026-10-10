package com.libraryz.data.api

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.libraryz.data.Recommendation

/**
 * Holds the "For You" recommendations. Same tri-state shape as the other
 * states (see [ContributionsState]):
 *   - items == null && error == null → loading
 *   - error != null → error
 *   - else → loaded (possibly empty)
 */
@Stable
class RecommendationsState(private val api: ApiClient) {
    var items: List<Recommendation>? by mutableStateOf(null)
        private set

    var error: String? by mutableStateOf(null)
        private set

    val loading: Boolean get() = items == null && error == null

    suspend fun refresh() {
        error = null
        try {
            items = api.recommendations()
        } catch (e: Throwable) {
            error = e.message ?: "Failed to load recommendations"
        }
    }

    /**
     * Hides a recommendation: removes it locally and persists the dismissal.
     * Returns what [undismiss] needs to put it back, or null if it wasn't listed.
     */
    suspend fun dismiss(workId: String): Dismissed? {
        api.dismissRecommendation(workId)
        val current = items ?: return null
        val index = current.indexOfFirst { it.work.id == workId }
        if (index < 0) return null
        items = current.filterIndexed { i, _ -> i != index }
        return Dismissed(current[index], index)
    }

    /** Undoes [dismiss]: clears the dismissal and restores the card where it was. */
    suspend fun undismiss(dismissed: Dismissed) {
        api.undismissRecommendation(dismissed.rec.work.id)
        val current = items ?: return
        if (current.any { it.work.id == dismissed.rec.work.id }) return
        items = current.toMutableList().apply {
            add(dismissed.index.coerceAtMost(size), dismissed.rec)
        }
    }

    /** A dismissed recommendation and the position it held. */
    class Dismissed(val rec: Recommendation, val index: Int)
}
