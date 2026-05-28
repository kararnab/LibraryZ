package com.libraryz.data.api

// Where the LibraryZ Go backend listens. Per-platform because the Android
// emulator can't reach the host's localhost — it has to go through 10.0.2.2.
expect val DefaultBaseUrl: String
