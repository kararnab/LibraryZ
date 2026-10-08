package com.libraryz.data

/** False where we haven't wired a save destination yet (iOS for v0). */
expect val isDownloadSupported: Boolean

/**
 * Persists [bytes] under [name] on disk (Android/Desktop) or triggers a
 * browser download (Wasm). Returns a user-facing description of where the
 * file ended up — display in a snackbar.
 */
expect suspend fun saveDownload(name: String, bytes: ByteArray): String

/**
 * Makes a server-provided filename safe to write: keeps only the last path
 * component, replaces characters filesystems reject, and strips leading dots.
 * Unlike [sanitizeFilename] it doesn't truncate (the server already caps the
 * length), so the extension survives. Null if nothing usable is left.
 */
fun safeDownloadName(s: String): String? =
    s.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[:*?\"<>|\\x00-\\x1F]"), "_")
        .trim().trimStart('.').trim()
        .takeIf { it.isNotEmpty() }

/** File-system-safe version of a string. Trims to 80 chars to keep paths reasonable. */
fun sanitizeFilename(s: String): String =
    s.replace(Regex("[/\\\\:*?\"<>|\\x00-\\x1F]"), "_").trim().take(80).ifEmpty { "untitled" }
