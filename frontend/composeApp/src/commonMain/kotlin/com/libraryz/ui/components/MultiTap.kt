package com.libraryz.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * A click handler that runs [action] only on the [taps]th click in quick
 * succession (each within [within] of the last), for settings a stray tap
 * shouldn't open. A slower click starts the count again.
 */
@Composable
fun rememberMultiTap(taps: Int, within: Duration = 400.milliseconds, action: () -> Unit): () -> Unit {
    require(taps >= 1) { "taps must be at least 1, was $taps" }
    val current by rememberUpdatedState(action)
    return remember(taps, within) {
        val counter = MultiTapCounter(taps, within)
        val handler: () -> Unit = { if (counter.tap(TimeSource.Monotonic.markNow())) current() }
        handler
    }
}

/** The counting behind [rememberMultiTap], apart from Compose so it can be tested. */
internal class MultiTapCounter(private val taps: Int, private val within: Duration) {
    private var count = 0
    private var last: ComparableTimeMark? = null

    /** Records a click at [now]; true when it completes a run of [taps]. */
    fun tap(now: ComparableTimeMark): Boolean {
        count = if (last?.let { now - it < within } == true) count + 1 else 1
        last = now
        if (count < taps) return false
        count = 0
        last = null
        return true
    }
}
