package com.libraryz

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The platform entry points tell the app when it leaves the screen or
 * closes, so what's waiting to be sent (telemetry) goes now rather than at
 * the next launch. [App] sets [send]; nothing depends on it running, since
 * what's waiting is also kept on the device.
 */
object AppExit {
    internal var send: (suspend () -> Unit)? = null

    // Outlives the composition: an activity being stopped or destroyed
    // mustn't cancel the send it started.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The app went to the background (Android onStop, the browser tab hidden). Doesn't wait. */
    fun backgrounded() {
        val s = send ?: return
        scope.launch { withTimeoutOrNull(BACKGROUND_MS) { s() } }
    }

    /** The app is about to exit (the desktop window closing): waits up to [timeoutMs]. */
    suspend fun beforeExit(timeoutMs: Long = EXIT_MS) {
        val s = send ?: return
        withTimeoutOrNull(timeoutMs) { s() }
    }

    private const val BACKGROUND_MS = 10_000L
    private const val EXIT_MS = 2_000L
}
