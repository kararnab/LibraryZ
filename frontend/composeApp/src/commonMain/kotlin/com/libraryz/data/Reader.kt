package com.libraryz.data

import androidx.compose.ui.graphics.ImageBitmap

/**
 * A loaded edition ready to render. Subtypes distinguish formats whose
 * UX is genuinely different — paged (PDF, later DJVU / CBZ) renders to
 * an [ImageBitmap] page-by-page; [TextReader] exposes a single flowing
 * string the screen scrolls natively. `ReaderScreen` `when`s over this
 * sealed type and renders accordingly.
 *
 * Always close when done — `ReaderScreen` owns this via `DisposableEffect`.
 */
sealed interface Reader {
    fun close()
}

/**
 * Rasterizes pages on demand. Implementations are not thread-safe; the
 * screen serializes render calls from a single `LaunchedEffect` coroutine.
 */
interface PagedReader : Reader {
    val pageCount: Int
    suspend fun renderPage(pageIndex: Int, widthPx: Int): ImageBitmap
}

/**
 * A whole-document text payload. UTF-8 decoded once at open time, then
 * rendered with Compose `Text` — no rasterization, no per-platform actual,
 * scales and reflows for free.
 */
class TextReader(val text: String) : Reader {
    override fun close() = Unit
}

class UnsupportedFormatException(format: String) :
    RuntimeException("No preview reader for format \"$format\" on this build")

/** Formats this build can preview. PDF is platform-gated; TXT is universal. */
val supportedPreviewFormats: Set<String>
    get() = buildSet {
        add("TXT")
        if (isPdfPreviewSupported) add("PDF")
    }

fun canPreview(format: String): Boolean =
    format.uppercase() in supportedPreviewFormats

/**
 * Opens [bytes] as a [Reader] for [format] (uppercase API string).
 * Throws [UnsupportedFormatException] for anything this build can't
 * render — gate UI on [canPreview] first.
 */
suspend fun openReader(bytes: ByteArray, format: String): Reader =
    when (format.uppercase()) {
        "PDF" -> openPdfReader(bytes)
        "TXT" -> TextReader(bytes.decodeToString())
        else -> throw UnsupportedFormatException(format)
    }
