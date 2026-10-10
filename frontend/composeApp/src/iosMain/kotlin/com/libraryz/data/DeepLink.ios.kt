package com.libraryz.data

// No app links registered on iOS yet; see DeepLink.kt.
actual fun launchDeepLink(): DeepLink? = null

actual val openInApp: ((url: String) -> Unit)? = null
