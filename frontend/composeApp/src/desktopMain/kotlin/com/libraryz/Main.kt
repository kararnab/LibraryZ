package com.libraryz

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.libraryz.ui.Fullscreen
import com.libraryz.ui.LocalFullscreen

fun main(args: Array<String>) {
    // Another copy is running and took the link (or the launch): done.
    if (!DesktopLinks.start(args)) return
    application {
        val state = rememberWindowState(size = DpSize(1280.dp, 800.dp))
        Window(
            onCloseRequest = ::exitApplication,
            title = "LibraryZ",
            state = state,
        ) {
            // A link or a second launch brings the window forward.
            val raise by DesktopLinks.raise.collectAsState()
            LaunchedEffect(raise) {
                if (raise == 0) return@LaunchedEffect
                if (state.isMinimized) state.isMinimized = false
                window.toFront()
                window.requestFocus()
            }
            val fullscreen = remember(state) { WindowFullscreen(state) }
            CompositionLocalProvider(LocalFullscreen provides fullscreen) { App() }
        }
    }
}

/** Full screen as a window placement; leaving it restores the previous one (maximized or not). */
private class WindowFullscreen(private val state: WindowState) : Fullscreen {
    private var before = WindowPlacement.Floating
    override val isSupported = true
    override val isOn get() = state.placement == WindowPlacement.Fullscreen
    override fun set(on: Boolean) {
        if (on == isOn) return
        if (on) before = state.placement
        state.placement = if (on) WindowPlacement.Fullscreen else before
    }
}
