package com.libraryz.data

// Android hands links to DeepLinkInbox (MainActivity).
actual fun launchDeepLink(): DeepLink? = null

actual val openInApp: ((url: String) -> Unit)? = null
