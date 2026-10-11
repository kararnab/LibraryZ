package com.libraryz.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A loaded edition ready to render. Subtypes distinguish formats whose
 * UX is genuinely different — paged (PDF, later DJVU / CBZ) renders to
 * an [ImageBitmap] page-by-page; [TextReader] exposes a single flowing
 * string (TXT, or an EPUB reduced to styled text) the screen scrolls or
 * paginates natively. `ReaderScreen` `when`s over this
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

    /**
     * The pages that are wider than tall (as displayed, rotation included);
     * a two-page spread shows those alone. Same threading rule as
     * [renderPage].
     */
    suspend fun landscapePages(): Set<Int> = emptySet()
}

/**
 * A whole-document text payload, decoded once at open time, then rendered
 * with Compose `Text` — no rasterization, no per-platform actual, scales and
 * reflows for free. [chapters] are offsets into [text], in order.
 */
class TextReader(val text: AnnotatedString, val chapters: List<Chapter>) : Reader {
    /** Plain text, with chapters found from its headings. */
    constructor(text: String) : this(AnnotatedString(text), chaptersOf(text))

    override fun close() = Unit
}

class UnsupportedFormatException(format: String) :
    RuntimeException("No preview reader for format \"$format\" on this build")

/** Formats this build can preview. PDF is platform-gated; TXT and EPUB are universal. */
val supportedPreviewFormats: Set<String>
    get() = buildSet {
        add("TXT")
        add("EPUB")
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
        "EPUB" -> withContext(Dispatchers.Default) {
            parseEpub(bytes).let { TextReader(it.text, it.chapters) }
        }
        else -> throw UnsupportedFormatException(format)
    }
