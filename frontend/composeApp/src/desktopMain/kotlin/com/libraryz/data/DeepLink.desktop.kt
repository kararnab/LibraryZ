package com.libraryz.data

// Desktop hands links to DeepLinkInbox (DesktopLinks).
actual fun launchDeepLink(): DeepLink? = null

actual val openInApp: ((url: String) -> Unit)? = null
