package com.libraryz.data.api

// 10.0.2.2 is the Android emulator's loopback alias for the host machine's
// localhost. On a real device, override at runtime via Settings (later) or
// rebuild with a different value.
actual val DefaultBaseUrl: String = "http://10.0.2.2:8080"
