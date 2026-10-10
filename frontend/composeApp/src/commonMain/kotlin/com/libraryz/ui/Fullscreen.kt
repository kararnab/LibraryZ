package com.libraryz.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Puts the app's window in full screen, for immersive reading. Platforms
 * provide one through [LocalFullscreen] around `App()`; where none is
 * provided, [isSupported] is false and the reader hides its button.
 * [isOn] is snapshot state, so reading it recomposes on change, including
 * changes the user makes outside the app (Esc in a browser, a system swipe).
 */
interface Fullscreen {
    val isSupported: Boolean
    val isOn: Boolean
    fun set(on: Boolean)
}

private object NoFullscreen : Fullscreen {
    override val isSupported = false
    override val isOn = false
    override fun set(on: Boolean) = Unit
}

val LocalFullscreen = staticCompositionLocalOf<Fullscreen> { NoFullscreen }
