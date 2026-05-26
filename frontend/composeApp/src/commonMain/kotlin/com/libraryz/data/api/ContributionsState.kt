package com.libraryz.data.api

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.libraryz.data.Contribution

/**
 * Holds the moderator review queue. Same tri-state shape as [WorksState]:
 *   - `items == null && error == null` → loading
 *   - `error != null` → error
 *   - else → loaded (possibly empty)
 *
 * [approve] and [reject] mutate the queue locally on success — the
 * approved/rejected card vanishes from the pending list without a full
 * refresh, which keeps the moderator's place in the queue.
 */
@Stable
class ContributionsState(private val api: ApiClient) {
    var items: List<Contribution>? by mutableStateOf(null)
        private set

    var error: String? by mutableStateOf(null)
        private set

    val loading: Boolean get() = items == null && error == null

    /** Number of pending entries currently in [items], for badge display. */
    val pendingCount: Int get() = items?.size ?: 0

    suspend fun refresh() {
        error = null
        try {
            items = api.listContributions(status = "pending")
        } catch (e: Throwable) {
            error = e.message ?: "Failed to load contributions"
        }
    }

    suspend fun approve(id: String) {
        api.approveContribution(id)
        items = items?.filterNot { it.id == id }
    }

    suspend fun reject(id: String) {
        api.rejectContribution(id)
        items = items?.filterNot { it.id == id }
    }
}
