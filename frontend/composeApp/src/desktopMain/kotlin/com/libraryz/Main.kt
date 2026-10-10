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
import com.libraryz.theme.LocalSystemDarkTheme
import com.libraryz.ui.Fullscreen
import com.libraryz.ui.LocalFullscreen
import java.awt.Toolkit

fun main(args: Array<String>) {
    // Another copy is running and took the link (or the launch): done.
    if (!DesktopLinks.start(args)) return
    setLinuxWindowClass("LibraryZ")
    LinuxColorScheme.start()
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
            val systemDark by LinuxColorScheme.dark.collectAsState()
            CompositionLocalProvider(
                LocalFullscreen provides fullscreen,
                LocalSystemDarkTheme provides systemDark,
            ) { App() }
        }
    }
}

/**
 * GNOME names a window (dock, Alt-Tab, top bar) after its X11 WM_CLASS, which
 * Java derives from the main class: "com-libraryz-MainKt". AWT has no API for
 * it, so set XToolkit's field before the first window opens. Reflection needs
 * --add-opens java.desktop/sun.awt.X11 (build.gradle.kts). Elsewhere, or on a
 * toolkit without the field (Wayland), it's a no-op.
 */
private fun setLinuxWindowClass(name: String) {
    if (!System.getProperty("os.name").startsWith("Linux")) return
    runCatching {
        val toolkit = Toolkit.getDefaultToolkit()
        toolkit.javaClass.getDeclaredField("awtAppClassName").apply {
            isAccessible = true
            set(toolkit, name)
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
