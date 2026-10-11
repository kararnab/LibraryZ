package com.libraryz.data

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/** An EPUB's reading text, table of contents and pictures, ready for [TextReader]. */
class EpubBook(
    val text: AnnotatedString,
    val chapters: List<Chapter>,
    val images: Map<String, BookImage>,
    val tables: List<BookTable>,
    val boxes: List<BookBox>,
)

class EpubException(message: String) : RuntimeException(message)

/**
 * Reads an EPUB (2 or 3) as reflowable text: the spine's XHTML documents in
 * order, one after another, keeping headings, emphasis, lists, paragraph
 * breaks, pictures and tables, and the parts of the book's CSS that carry
 * meaning (alignment, indents, colours, relative sizes, boxes, embedded
 * fonts; see [BookBuilder]). The reader's own typeface, size and themes then
 * apply to the rest, as they do to TXT. Pictures are [BookImage]s, read from
 * the archive again when they're drawn.
 *
 * Chapters come from the EPUB 3 nav document, else the EPUB 2 NCX, else the
 * book's h1–h3 headings, each resolved to a character offset in [EpubBook.text].
 */
fun parseEpub(bytes: ByteArray): EpubBook {
    val zip = try {
        ZipArchive(bytes)
    } catch (e: ZipException) {
        throw EpubException("This EPUB is damaged (${e.message}).")
    }
    try {
        val opfPath = zip.readText("META-INF/container.xml")?.let { xml ->
            markupTokens(xml).firstNotNullOfOrNull { (it as? MarkupToken.Start)?.takeIf { s -> s.name == "rootfile" }?.attrs?.get("full-path") }
        } ?: zip.entries.firstOrNull { it.name.endsWith(".opf", ignoreCase = true) }?.name
            ?: throw EpubException("This EPUB has no package document.")
        val opf = zip.readText(opfPath) ?: throw EpubException("This EPUB's package document is missing.")
        val opfDir = dirOf(opfPath)

        val items = HashMap<String, ManifestItem>()
        val spine = ArrayList<String>()
        var ncxId: String? = null
        var uniqueId: String? = null
        val identifiers = LinkedHashMap<String, String>()
        var identifierId: String? = null
        for (t in markupTokens(opf)) {
            if (t is MarkupToken.Text) {
                identifierId?.let { identifiers[it] = (identifiers[it].orEmpty() + t.text) }
                continue
            }
            if (t is MarkupToken.End && t.name == "identifier") identifierId = null
            if (t !is MarkupToken.Start) continue
            when (t.name) {
                "package" -> uniqueId = t.attrs["unique-identifier"]
                "identifier" -> identifierId = t.attrs["id"] ?: "#${identifiers.size}"
                "item" -> {
                    val id = t.attrs["id"] ?: continue
                    val href = t.attrs["href"] ?: continue
                    items[id] = ManifestItem(
                        path = resolvePath(opfDir, href.substringBefore('#')),
                        type = t.attrs["media-type"].orEmpty().lowercase(),
                        properties = t.attrs["properties"].orEmpty().split(' '),
                    )
                }
                "spine" -> ncxId = t.attrs["toc"]
                "itemref" -> t.attrs["idref"]?.let { spine += it }
            }
        }

        val images = HashMap<String, BookImage?>()
        val sheets = HashMap<String, ParsedSheet?>()
        val sheetAt = { path: String ->
            sheets.getOrPut(path) {
                zip.readText(path)?.let { css -> ParsedSheet(parseCss(css), parseFontFaces(css).map { it.resolved(dirOf(path)) }) }
            }
        }
        val fontAt = embeddedFonts(zip, uniqueId?.let(identifiers::get) ?: identifiers.values.firstOrNull(), identifiers.values)
        val book = BookBuilder(sheetAt = sheetAt, fontAt = fontAt, imageAt = { path, alt ->
            images.getOrPut(path) {
                val entry = zip.find(path) ?: return@getOrPut null
                val bytes = runCatching { zip.read(entry) }.getOrNull() ?: return@getOrPut null
                val raster = imageSize(bytes)
                val size = raster ?: svgSize(bytes) ?: return@getOrPut null
                val (w, h) = size
                // Small enough to be a glyph or an ornament in the line, not a figure.
                val block = h > INLINE_IMAGE_MAX_HEIGHT || w > INLINE_IMAGE_MAX_HEIGHT * 4
                BookImage(w, h, alt.collapseWhitespace(), block, vector = raster == null) { zip.read(entry) }
            }
        })
        val documents = ArrayList<String>()
        for (id in spine) {
            val item = items[id] ?: continue
            if (!item.isDocument) continue
            val xhtml = zip.readText(item.path) ?: continue
            book.addDocument(item.path, xhtml)
            documents += item.path
        }
        val text = book.build()
        if (text.text.isBlank()) throw EpubException("This EPUB has nothing this reader can show.")

        val toc = tableOfContents(zip, items, ncxId)
            .mapNotNull { e -> book.offsetOf(e.path, e.fragment)?.let { Chapter(it, e.title, e.depth) } }
            .distinct()
            .sortedBy { it.offset }
        val listed = toc.ifEmpty { book.headings }
        val chapters = (listed + unlistedChapters(text.text, documents.mapNotNull { book.offsetOf(it, "") }, listed))
            .sortedBy { it.offset }
        return EpubBook(text, chapters, images.filterValues { it != null }.mapValues { it.value!! }, book.tables, book.boxes)
    } catch (e: ZipException) {
        throw EpubException("This EPUB is damaged (${e.message}).")
    }
}

/**
 * Chapters the book's table of contents leaves out: a spine document that
 * opens with a chapter heading ("CHAPTER II") no listed chapter starts at.
 * Some converters (Project Gutenberg's, for one) list only the front and
 * back matter, so without these a whole novel reads as its "List of the
 * Plates" in the running head and the Contents sheet.
 */
internal fun unlistedChapters(text: String, documentStarts: List<Int>, listed: List<Chapter>): List<Chapter> =
    documentStarts.distinct().mapNotNull { start ->
        // The document's first line of text.
        val lineStart = (start until text.length).firstOrNull { !text[it].isWhitespace() } ?: return@mapNotNull null
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val title = chapterHeading(text.substring(lineStart, lineEnd)) ?: return@mapNotNull null
        if (listed.any { it.offset in start..lineEnd }) null else Chapter(lineStart, title)
    }

/** A stylesheet's rules and its `@font-face`s (font paths resolved in the archive). */
internal class ParsedSheet(val rules: List<CssRule>, val faces: List<ResolvedFace>)

/** An `@font-face`'s font file in the archive. */
internal data class ResolvedFace(val family: String, val path: String, val weight: Int, val italic: Boolean)

internal fun CssFontFace.resolved(dir: String) = ResolvedFace(family, resolvePath(dir, src.substringBefore('#').substringBefore('?')), weight, italic)

/**
 * Loads the font families [BookBuilder] asks for, once each: each face's
 * file read from [zip], un-obfuscated per `META-INF/encryption.xml` (keyed
 * by [uniqueId], or for Adobe's scheme a `urn:uuid:` among [identifiers]),
 * and kept only if it's a TrueType / OpenType font the platform can load.
 * Fonts under real encryption (DRM) are skipped.
 */
private fun embeddedFonts(zip: ZipArchive, uniqueId: String?, identifiers: Collection<String>): (List<ResolvedFace>) -> FontFamily? {
    val obfuscated = HashMap<String, FontObfuscation?>()
    zip.readText("META-INF/encryption.xml")?.let { xml ->
        var algorithm: String? = null
        for (t in markupTokens(xml)) {
            if (t !is MarkupToken.Start) continue
            when (t.name) {
                "encryptionmethod" -> algorithm = t.attrs["algorithm"]
                "cipherreference" -> t.attrs["uri"]?.let { uri ->
                    obfuscated[resolvePath("", uri)] = FontObfuscation.entries.firstOrNull { it.algorithm == algorithm }
                }
            }
        }
    }
    val adobeKey = identifiers.firstOrNull { it.trim().startsWith("urn:uuid:") }
    val families = HashMap<List<ResolvedFace>, FontFamily?>()
    return { faces ->
        families.getOrPut(faces) {
            val fonts = faces.mapNotNull { face ->
                val bytes = zip.find(face.path)?.let { runCatching { zip.read(it) }.getOrNull() } ?: return@mapNotNull null
                if (face.path in obfuscated) {
                    val scheme = obfuscated[face.path] ?: return@mapNotNull null
                    val key = (if (scheme == FontObfuscation.Adobe) adobeKey else uniqueId) ?: return@mapNotNull null
                    if (!deobfuscate(bytes, scheme, key)) return@mapNotNull null
                }
                if (!isSfnt(bytes)) return@mapNotNull null
                loadFont(sha1(bytes).hex(), bytes, FontWeight(face.weight), if (face.italic) FontStyle.Italic else FontStyle.Normal)
            }
            if (fonts.isEmpty()) null else FontFamily(fonts)
        }
    }
}

/** Pictures up to this many pixels high (and four times as wide) sit in the line of text. */
private const val INLINE_IMAGE_MAX_HEIGHT = 40

internal fun String.collapseWhitespace(): String = trim().replace(Regex("\\s+"), " ")

/** "OEBPS/Text/ch1.xhtml" → "OEBPS/Text/"; a root-level file → "". */
internal fun dirOf(path: String): String = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

/** [href] (percent-encoded, maybe with `..`) against [dir], as a zip entry name. */
internal fun resolvePath(dir: String, href: String): String {
    val decoded = percentDecode(href)
    val full = if (decoded.startsWith("/")) decoded.drop(1) else dir + decoded
    val parts = ArrayList<String>()
    for (seg in full.split('/')) {
        when (seg) {
            "", "." -> Unit
            ".." -> parts.removeLastOrNull()
            else -> parts += seg
        }
    }
    return parts.joinToString("/")
}

private fun percentDecode(s: String): String {
    if ('%' !in s) return s
    val bytes = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        val hex = if (c == '%' && i + 2 <= s.lastIndex) s.substring(i + 1, i + 3).toIntOrNull(16) else null
        if (hex != null) {
            bytes += hex.toByte()
            i += 3
        } else {
            c.toString().encodeToByteArray().forEach { bytes += it }
            i++
        }
    }
    return bytes.toByteArray().decodeToString()
}
