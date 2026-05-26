package com.libraryz.data

/** False on iOS for v0. True elsewhere. */
expect val isPdfPreviewSupported: Boolean

/**
 * Opens [bytes] as a PDF [PagedReader]. Suspend because Wasm's pdf.js
 * loads asynchronously (returns Promises). Throws on unsupported
 * platforms — check [isPdfPreviewSupported] first, or prefer the
 * format-agnostic [openReader] in `Reader.kt`.
 */
expect suspend fun openPdfReader(bytes: ByteArray): PagedReader
