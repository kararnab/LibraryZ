package com.libraryz.data

import kotlinx.browser.window

/**
 * The page URL, e.g. https://library.example.org/reset-password?token=….
 * Once read, the token is taken out of the address bar (and history), so it
 * isn't left lying around or replayed by a reload.
 */
actual fun launchDeepLink(): DeepLink? {
    val link = parseDeepLink(window.location.href) ?: return null
    window.history.replaceState(null, "", "/")
    return link
}

/** The browser asks whether to open the app; with no app installed, nothing happens. */
actual val openInApp: ((url: String) -> Unit)? = { url -> window.location.href = url }
