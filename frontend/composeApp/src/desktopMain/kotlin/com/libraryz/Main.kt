package com.libraryz

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.libraryz.theme.LocalReduceMotion
import com.libraryz.theme.LocalSystemDarkTheme
import com.libraryz.ui.Fullscreen
import com.libraryz.ui.LocalFullscreen
import java.awt.Taskbar
import java.awt.Toolkit
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.DefaultWindowExceptionHandlerFactory
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import com.libraryz.data.telemetry.reportUiCrash

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    // Another copy is running and took the link (or the launch): done.
    if (!DesktopLinks.start(args)) return
    setLinuxWindowClass(LinuxWindowClass)
    LinuxColorScheme.start()
    val icon = appIcon()?.let { BitmapPainter(it.toComposeImageBitmap()) }
    application {
        val state = rememberWindowState(size = DpSize(1280.dp, 800.dp))
        // The AWT window, for hiding it the moment it's closed.
        val awtWindow = remember { arrayOfNulls<ComposeWindow>(1) }
        // A crash inside the UI: Compose catches it, so report it here, show
        // Compose's usual error dialog, then quit. Left alone, the window
        // closes but the process stays, holding the single-instance lock.
        val onUiCrash = remember {
            WindowExceptionHandlerFactory { window ->
                WindowExceptionHandler { e ->
                    reportUiCrash(e)
                    DefaultWindowExceptionHandlerFactory.exceptionHandler(window).onException(e)
                    exitApplication()
                }
            }
        }
        CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides onUiCrash) {
            Window(
                // Hidden at once; waiting telemetry gets up to 2 s to go out.
                onCloseRequest = {
                    awtWindow[0]?.isVisible = false
                    runBlocking { AppExit.beforeExit() }
                    exitApplication()
                },
                title = "LibraryZ",
                icon = icon,
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
                awtWindow[0] = window
                val fullscreen = remember(state) { WindowFullscreen(state, window) }
                val systemDark by LinuxColorScheme.dark.collectAsState()
                CompositionLocalProvider(
                    LocalFullscreen provides fullscreen,
                    LocalSystemDarkTheme provides systemDark,
                    LocalReduceMotion provides DesktopReduceMotion.enabled,
                ) { App() }
            }
        }
    }
}

/**
 * The "Lz" mark (branding/generate_icons.py). The window's icon is what Linux
 * docks and Windows' taskbar show; macOS's dock reads [Taskbar] instead, and
 * installed apps use the packaging icon (build.gradle.kts).
 */
private fun appIcon(): BufferedImage? {
    val image = object {}.javaClass.getResourceAsStream("/libraryz-icon.png")
        ?.use(ImageIO::read) ?: return null
    runCatching {
        if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
            Taskbar.getTaskbar().iconImage = image
        }
    }
    return image
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

/**
 * Full screen as a window placement. Leaving goes through Floating, set on
 * the window itself: Compose's Maximized only maximizes and leaves full
 * screen on, so restoring a window that was maximized before kept it full
 * screen for good. GNOME also drops a maximize sent while it's leaving full
 * screen, so such a window comes back as a normal one.
 */
private class WindowFullscreen(private val state: WindowState, private val window: ComposeWindow) : Fullscreen {
    override val isSupported = true
    override val isOn get() = state.placement == WindowPlacement.Fullscreen
    override fun set(on: Boolean) {
        if (on == isOn) return
        if (on) state.placement = WindowPlacement.Fullscreen
        else window.placement = WindowPlacement.Floating
    }
}
