package com.libraryz.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
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
    /** Blocks the book draws a box around, in text order. */
    val boxes: List<BookBox> = emptyList(),
    /** Offsets in [text] where a page must start (an EPUB's documents, CSS page breaks), ascending. */
    val pageStarts: List<Int> = emptyList(),
    /**
     * The [pageStarts] where an EPUB's next document begins. Two pages a
     * spread, each starts a spread on the left-hand page; when the page
     * before it is a left-hand one, the right-hand page beside that stays
     * blank.
     */
    val spreadStarts: List<Int> = emptyList(),
) : Reader {
    /** Plain text, with chapters found from its headings. */
    constructor(text: String) : this(AnnotatedString(text), chaptersOf(text))

    /** Whether the book sets any of its text in fonts it embeds (monospace is the platform's). */
    val hasBookFonts: Boolean by lazy {
        text.spanStyles.any { it.item.fontFamily != null && it.item.fontFamily != FontFamily.Monospace }
    }

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
    /** SVG, rasterized at the size it's drawn; else a bitmap format. */
    val vector: Boolean = false,
    /** A block picture's widest, as a fraction of the column (the book's width and side margins). */
    val widthFraction: Float = 1f,
    private val load: () -> ByteArray,
) {
    fun bytes(): ByteArray = load()

    /** The same picture, at most [fraction] of the column wide. */
    fun scaled(fraction: Float) = BookImage(width, height, alt, block, vector, fraction, load)
}

/**
 * A table in a [TextReader]'s text, drawn as a grid. Each row is one
 * [IMAGE_CHAR] on a line of its own, annotated with [TABLE_TAG] =
 * "<table index>:<row index>" and marked as inline content under the same
 * key, so a long table breaks between rows across pages. The screen sizes
 * the columns and rows for the column it has.
 */
class BookTable(
    val rows: List<List<BookCell>>,
    val bordered: Boolean,
    /** The widths the book's `<col>`s give, column by column (null = not set). */
    val columns: List<BookWidth?> = emptyList(),
    /** The table's own width, if the book sets one. */
    val width: BookWidth? = null,
)

/** One cell: its text (styled like the book's, pictures included), spanning [span] columns. */
class BookCell(
    val text: AnnotatedString,
    val header: Boolean,
    val span: Int,
    val background: Color?,
    /** The width the book gives the cell (`width` attribute or CSS), if any. */
    val width: BookWidth? = null,
)

/** A width a book sets: a [fraction] of the table's (or column's) width, or [em]s of the reading text. */
data class BookWidth(val fraction: Float = 0f, val em: Float = 0f) {
    companion object {
        /** A CSS `width` or HTML `width` attribute ("30%", "120" pixels, "8em"); null for auto or nonsense. */
        fun parse(value: String?): BookWidth? {
            val v = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            if (v.endsWith("%")) {
                val f = v.dropLast(1).trim().toFloatOrNull() ?: return null
                return if (f > 0f) BookWidth(fraction = (f / 100f).coerceAtMost(1f)) else null
            }
            val em = v.toFloatOrNull()?.let { it / 16f } ?: cssEm(v) ?: return null
            return if (em > 0f) BookWidth(em = em) else null
        }
    }
}

/**
 * A block the book draws a box around (a CSS background or border): its
 * lines from [start] until [end] in the text, the box's left edge [left] ems
 * in from the column's. Its text is padded in from the edge already.
 * Only the [sides] the book borders are drawn ([BookBox.TOP] and so on).
 *
 * A [rule] is a blank block with only a top or bottom border (a book's
 * decorative line): one line across the middle of its single line, inset
 * by [left] ems plus [leftFraction] of the column, and [rightFraction] of it
 * from the right.
 */
class BookBox(
    val start: Int,
    val end: Int,
    val left: Float,
    val background: Color?,
    val border: Boolean,
    val borderColor: Color?,
    val sides: Int = ALL,
    val rule: Boolean = false,
    val leftFraction: Float = 0f,
    val rightFraction: Float = 0f,
) {
    companion object {
        const val TOP = 1
        const val RIGHT = 2
        const val BOTTOM = 4
        const val LEFT = 8
        const val ALL = TOP or RIGHT or BOTTOM or LEFT
    }
}

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
            parseEpub(bytes).let { TextReader(it.text, it.chapters, it.images, it.tables, it.boxes, it.pageStarts, it.spreadStarts) }
        }
        else -> throw UnsupportedFormatException(format)
    }
