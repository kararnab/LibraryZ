package com.libraryz.data

import kotlinx.browser.window

/** The page URL, e.g. https://library.example.org/reset-password?token=…. */
actual fun launchDeepLink(): DeepLink? = parseDeepLink(window.location.href)
