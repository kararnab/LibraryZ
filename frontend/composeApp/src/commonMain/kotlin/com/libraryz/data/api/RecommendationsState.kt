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

    /** Hides a recommendation: removes it locally and persists the dismissal. */
    suspend fun dismiss(workId: String) {
        api.dismissRecommendation(workId)
        items = items?.filterNot { it.work.id == workId }
    }
}
