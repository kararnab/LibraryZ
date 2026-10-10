package com.libraryz

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.libraryz.ui.Fullscreen
import com.libraryz.ui.LocalFullscreen
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val fullscreen = BrowserFullscreen()
    ComposeViewport(document.body!!) {
        CompositionLocalProvider(LocalFullscreen provides fullscreen) { App() }
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
