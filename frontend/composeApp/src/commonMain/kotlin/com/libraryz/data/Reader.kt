package com.libraryz.data

import androidx.compose.ui.graphics.Color
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
class TextReader(
    val text: AnnotatedString,
    val chapters: List<Chapter>,
    /** The pictures in [text], by the key of their [IMAGE_TAG] annotation. */
    val images: Map<String, BookImage> = emptyMap(),
    /** The tables in [text]; each row is one [TABLE_TAG] placeholder (see [BookTable]). */
    val tables: List<BookTable> = emptyList(),
) : Reader {
    /** Plain text, with chapters found from its headings. */
    constructor(text: String) : this(AnnotatedString(text), chaptersOf(text))

    override fun close() = Unit
}

/**
 * A picture in a [TextReader]'s text. Each occurrence is one [IMAGE_CHAR]
 * annotated with [IMAGE_TAG] = its key, and also marked as inline content
 * under the same key, so `Text` can draw it in a placeholder the screen sizes
 * from [width] × [height] (pixels). A [block] image is a paragraph of its
 * own, scaled to the column; an inline one sits in the line, one em high.
 * [bytes] reads the encoded file again each time, so only the pictures on
 * screen are ever decoded.
 */
class BookImage(
    val width: Int,
    val height: Int,
    val alt: String,
    val block: Boolean,
    private val load: () -> ByteArray,
) {
    fun bytes(): ByteArray = load()
}

/**
 * A table in a [TextReader]'s text, drawn as a grid. Each row is one
 * [IMAGE_CHAR] on a line of its own, annotated with [TABLE_TAG] =
 * "<table index>:<row index>" and marked as inline content under the same
 * key, so a long table breaks between rows across pages. The screen sizes
 * the columns and rows for the column it has.
 */
class BookTable(val rows: List<List<BookCell>>, val bordered: Boolean)

/** One cell: its text (styled like the book's, pictures included), spanning [span] columns. */
class BookCell(val text: AnnotatedString, val header: Boolean, val span: Int, val background: Color?)

const val IMAGE_TAG = "libraryz.image"
const val TABLE_TAG = "libraryz.table"
const val IMAGE_CHAR = "\uFFFC"

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
            parseEpub(bytes).let { TextReader(it.text, it.chapters, it.images, it.tables) }
        }
        else -> throw UnsupportedFormatException(format)
    }
