package com.libraryz

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.libraryz.ui.Fullscreen
import com.libraryz.ui.LocalFullscreen
import com.libraryz.theme.LocalReduceMotion
import kotlinx.browser.document
import kotlinx.browser.window

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val fullscreen = BrowserFullscreen()
    val reduceMotion = ReduceMotionQuery()
    // A hidden tab may be closed next: send waiting telemetry now.
    document.addEventListener("visibilitychange", { if (pageHidden()) AppExit.backgrounded() })
    ComposeViewport(document.body!!) {
        CompositionLocalProvider(
            LocalFullscreen provides fullscreen,
            LocalReduceMotion provides reduceMotion.on,
        ) { App() }
    }
}

@JsFun("() => document.visibilityState === 'hidden'")
private external fun pageHidden(): Boolean

/** The browser's `prefers-reduced-motion`, followed live. */
private class ReduceMotionQuery {
    private val query = window.matchMedia("(prefers-reduced-motion: reduce)")
    var on by mutableStateOf(query.matches)
        private set

    init {
        query.addEventListener("change", { on = query.matches })
    }
}

/**
 * The page's Fullscreen API. The browser can leave full screen on its own
 * (Esc), so state follows `fullscreenchange` rather than our own calls.
 */
private class BrowserFullscreen : Fullscreen {
    private var on by mutableStateOf(document.fullscreenElement != null)

    init {
        document.addEventListener("fullscreenchange") { on = document.fullscreenElement != null }
    }

    override val isSupported = document.fullscreenEnabled
    override val isOn get() = on
    override fun set(on: Boolean) {
        when {
            on && document.fullscreenElement == null -> document.documentElement?.requestFullscreen()
            !on && document.fullscreenElement != null -> document.exitFullscreen()
        }
    }
}
