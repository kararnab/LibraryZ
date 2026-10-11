package com.libraryz.data

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em

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
        for (id in spine) {
            val item = items[id] ?: continue
            if (!item.isDocument) continue
            val xhtml = zip.readText(item.path) ?: continue
            book.addDocument(item.path, xhtml)
        }
        val text = book.build()
        if (text.text.isBlank()) throw EpubException("This EPUB has nothing this reader can show.")

        val toc = tableOfContents(zip, items, ncxId)
            .mapNotNull { e -> book.offsetOf(e.path, e.fragment)?.let { Chapter(it, e.title, e.depth) } }
            .distinct()
            .sortedBy { it.offset }
        return EpubBook(text, toc.ifEmpty { book.headings }, images.filterValues { it != null }.mapValues { it.value!! }, book.tables, book.boxes)
    } catch (e: ZipException) {
        throw EpubException("This EPUB is damaged (${e.message}).")
    }
}

/** A stylesheet's rules and its `@font-face`s (font paths resolved in the archive). */
private class ParsedSheet(val rules: List<CssRule>, val faces: List<ResolvedFace>)

/** An `@font-face`'s font file in the archive. */
private data class ResolvedFace(val family: String, val path: String, val weight: Int, val italic: Boolean)

private fun CssFontFace.resolved(dir: String) = ResolvedFace(family, resolvePath(dir, src.substringBefore('#').substringBefore('?')), weight, italic)

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

private class ManifestItem(val path: String, val type: String, val properties: List<String>) {
    val isDocument: Boolean
        get() = type == "application/xhtml+xml" || type == "text/html" ||
            (type.isEmpty() && (path.endsWith(".xhtml", true) || path.endsWith(".html", true) || path.endsWith(".htm", true)))
}

private class TocEntry(val title: String, val path: String, val fragment: String, val depth: Int)

private fun tableOfContents(zip: ZipArchive, items: Map<String, ManifestItem>, ncxId: String?): List<TocEntry> {
    items.values.firstOrNull { "nav" in it.properties }?.let { nav ->
        zip.readText(nav.path)?.let { navToc(it, dirOf(nav.path)) }?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    val ncx = ncxId?.let(items::get) ?: items.values.firstOrNull { it.type == "application/x-dtbncx+xml" }
    return ncx?.let { zip.readText(it.path) }?.let { ncxToc(it, dirOf(ncx.path)) }.orEmpty()
}

/** The EPUB 3 nav document's `<nav epub:type="toc">` (else its first nav): each link, by list depth. */
private fun navToc(xhtml: String, dir: String): List<TocEntry> {
    val tokens = markupTokens(xhtml)
    val navs = tokens.withIndex().filter { (_, t) -> t is MarkupToken.Start && t.name == "nav" }
    val start = (navs.firstOrNull { (_, t) ->
        val attrs = (t as MarkupToken.Start).attrs
        "toc" in attrs["epub:type"].orEmpty().split(' ') || attrs["role"] == "doc-toc"
    } ?: navs.firstOrNull())?.index ?: return emptyList()
    val out = ArrayList<TocEntry>()
    var depth = 0
    var href: String? = null
    val label = StringBuilder()
    for (t in tokens.subList(start + 1, tokens.size)) {
        when {
            t is MarkupToken.End && t.name == "nav" -> break
            t is MarkupToken.Start && t.name == "ol" && !t.selfClosing -> depth++
            t is MarkupToken.End && t.name == "ol" -> depth--
            t is MarkupToken.Start && t.name == "a" -> { href = t.attrs["href"]; label.clear() }
            t is MarkupToken.Text && href != null -> label.append(t.text)
            t is MarkupToken.End && t.name == "a" -> {
                href?.let { out += tocEntry(label.toString(), it, dir, (depth - 1).coerceAtLeast(0)) }
                href = null
            }
        }
    }
    return out.filter { it.title.isNotEmpty() }
}

/** The EPUB 2 NCX's navPoints, by nesting depth. */
private fun ncxToc(xml: String, dir: String): List<TocEntry> {
    val out = ArrayList<TocEntry>()
    var depth = 0
    var inText = false
    val label = StringBuilder()
    for (t in markupTokens(xml)) {
        when {
            t is MarkupToken.Start && t.name == "navpoint" -> { depth++; label.clear() }
            t is MarkupToken.End && t.name == "navpoint" -> depth--
            t is MarkupToken.Start && t.name == "text" -> inText = true
            t is MarkupToken.End && t.name == "text" -> inText = false
            t is MarkupToken.Text && inText && depth > 0 -> label.append(t.text)
            t is MarkupToken.Start && t.name == "content" && depth > 0 ->
                t.attrs["src"]?.let { out += tocEntry(label.toString(), it, dir, depth - 1) }
        }
    }
    return out.filter { it.title.isNotEmpty() }
}

private fun tocEntry(label: String, href: String, dir: String, depth: Int) = TocEntry(
    title = label.collapseWhitespace(),
    path = resolvePath(dir, href.substringBefore('#')),
    fragment = href.substringAfter('#', ""),
    depth = depth,
)

/**
 * Turns XHTML into one [AnnotatedString], styled by the book's own CSS
 * where the reader can follow it (see [Stylesheet]): whitespace collapsed
 * as a browser would, inline styles and colours as spans, pictures as
 * [IMAGE_CHAR]s (see [BookImage]) when [imageAt] can read them, else their
 * alt text, and tables as one placeholder per row (see [BookTable]).
 *
 * Blocks are separated by a blank line unless the book sets both margins
 * between them to 0 (the indented-paragraph style), then by a line break.
 * Alignment, indents and left margins become paragraph styles over
 * "regions" of consecutive blocks that share one: regions never overlap,
 * and where one ends the line break is implied, so a region carries its
 * trailing newline instead of the next one leading with it.
 *
 * Remembers where every document and `id` landed so the table of contents
 * can point into the text.
 */
private class BookBuilder(
    private val imageAt: (path: String, alt: String) -> BookImage?,
    private val sheetAt: (path: String) -> ParsedSheet?,
    // The book's embedded fonts, by the faces of one family.
    private val fontAt: (List<ResolvedFace>) -> FontFamily? = { null },
    val tables: MutableList<BookTable> = ArrayList(),
    val boxes: MutableList<BookBox> = ArrayList(),
    // Building one table cell: nested tables flatten to text.
    private val inCell: Boolean = false,
) {
    private val out = AnnotatedString.Builder()
    private val anchors = HashMap<String, Int>()
    private val pendingAnchors = ArrayList<String>()
    val headings = ArrayList<Chapter>()

    // A block ended: the next text starts a new paragraph.
    private var breakPending = false
    // Whitespace was seen since the last character.
    private var spacePending = false
    // At the start of a block or line, so leading whitespace is dropped.
    private var atBlockStart = true
    // Nothing written in this block yet, so a <br> has nothing to end.
    private var blockEmpty = true
    // An li's marker was just written: its first inner block shouldn't break the line.
    private var holdBreak = false
    private var preDepth = 0
    private var lastWritten: Char? = null
    // Elements being skipped (head, script, display: none…), innermost last.
    private val skipStack = ArrayList<String>()

    private var sheet = Stylesheet.EMPTY
    // The document's @font-faces by family.
    private var faces: Map<String, List<ResolvedFace>> = emptyMap()
    // The elements around this builder's content: a cell's table and row.
    private var basePath: List<CssElement> = emptyList()

    private class Open(
        val name: String,
        val start: Int,
        val styles: List<SpanStyle>,
        val heading: Int,
        val block: Boolean,
        val css: Map<String, String>,
        val element: CssElement,
        /** Font size against the reader's text size, this element's and its parents' factors multiplied. */
        val scale: Float = 1f,
        /** A block the book draws a box around. */
        val box: Boolean = false,
    ) {
        // Where the element's content starts, if a paragraph break came after its start tag.
        var contentStart = -1
    }
    private val stack = ArrayList<Open>()
    private class Span(val style: SpanStyle, val start: Int, val end: Int, val depth: Int)
    private val spans = ArrayList<Span>()
    private val lists = ArrayList<IntArray>() // [next number], or [-1] for a bulleted list
    private var headingOpen = false
    private val headingText = StringBuilder()

    // The current paragraph-style region: where it started and its style (null = the reader's own).
    private var regionStart = 0
    private var regionStyle: ParagraphStyle? = null
    // The space owed before the next block, in ems: the largest margin of the blocks that ended or began since the last one.
    private var gap = 0f
    // An <hr> centers its stars whatever surrounds it.
    private var centerNext = false
    // A table's rows just ended: the next block starts a region, its blank line leading.
    private var afterTable = false

    // A table being collected, until its end tag.
    private var tableTokens: ArrayList<MarkupToken>? = null
    private var tableDepth = 0
    private var tableStart: MarkupToken.Start? = null
    private var tablePath: List<CssElement> = emptyList()

    fun offsetOf(path: String, fragment: String): Int? =
        (if (fragment.isNotEmpty()) anchors["$path#$fragment"] else null) ?: anchors[path]

    fun addDocument(path: String, xhtml: String) {
        val tokens = markupTokens(xhtml)
        sheet = stylesheetOf(path, tokens)
        gap = maxOf(gap, 1f)
        block()
        pendingAnchors += path
        run(path, tokens)
        while (stack.isNotEmpty()) closeTop()
        skipStack.clear()
        preDepth = 0
        lists.clear()
        resolveAnchors()
    }

    /** One table cell's text: [tokens] from its start tag to its end, inside [ancestors]. */
    fun cell(path: String, sheet: Stylesheet, faces: Map<String, List<ResolvedFace>>, ancestors: List<CssElement>, tokens: List<MarkupToken>): AnnotatedString {
        this.sheet = sheet
        this.faces = faces
        basePath = ancestors
        block()
        run(path, tokens)
        while (stack.isNotEmpty()) closeTop()
        return build()
    }

    fun build(): AnnotatedString {
        closeRegion()
        // Outer spans first, so an inner element's colour or weight wins over its parent's.
        spans.sortedWith(compareBy<Span>({ it.start }, { -it.end }, { it.depth }))
            .forEach { out.addStyle(it.style, it.start, it.end) }
        spans.clear()
        return out.toAnnotatedString()
    }

    private fun run(path: String, tokens: List<MarkupToken>) {
        for (t in tokens) {
            val buffer = tableTokens
            if (buffer != null) {
                if (t is MarkupToken.Start && t.name == "table" && !t.selfClosing) tableDepth++
                if (t is MarkupToken.End && t.name == "table" && --tableDepth == 0) {
                    tableTokens = null
                    table(path, tableStart!!, buffer)
                } else {
                    buffer += t
                }
                continue
            }
            when (t) {
                is MarkupToken.Start -> open(path, t)
                is MarkupToken.End -> close(t.name)
                is MarkupToken.Text -> if (skipStack.isEmpty()) text(t.text)
            }
        }
        // A table left open at the end of its document.
        tableTokens?.let {
            tableTokens = null
            table(path, tableStart!!, it)
        }
    }

    /** The document's `<link rel="stylesheet">`s and `<style>` blocks, in order; their @font-faces into [faces]. */
    private fun stylesheetOf(path: String, tokens: List<MarkupToken>): Stylesheet {
        val sources = ArrayList<List<CssRule>>()
        val found = ArrayList<ResolvedFace>()
        var style: StringBuilder? = null
        for (t in tokens) {
            when {
                t is MarkupToken.Start && t.name == "link" && "stylesheet" in t.attrs["rel"].orEmpty().lowercase() ->
                    t.attrs["href"]?.let { sheetAt(resolvePath(dirOf(path), it.substringBefore('#'))) }?.let {
                        sources += it.rules
                        found += it.faces
                    }
                t is MarkupToken.Start && t.name == "style" && !t.selfClosing -> style = StringBuilder()
                t is MarkupToken.Text -> style?.append(t.text)
                t is MarkupToken.End && t.name == "style" -> {
                    style?.let {
                        sources += parseCss(it.toString())
                        found += parseFontFaces(it.toString()).map { f -> f.resolved(dirOf(path)) }
                    }
                    style = null
                }
            }
        }
        faces = found.groupBy { it.family }
        return if (sources.isEmpty()) Stylesheet.EMPTY else Stylesheet(sources)
    }

    /**
     * The font an element's `font-family` asks for: the first listed family
     * the book embeds, or monospace (code); other generic families and fonts
     * the book doesn't carry leave the reader's own.
     */
    private fun fontFamilyOf(css: Map<String, String>): FontFamily? {
        val families = css["font-family"]?.let(::cssFontFamilies) ?: return null
        for (f in families) {
            faces[f]?.let { faceList -> fontAt(faceList)?.let { return it } }
            if (f == "monospace") return FontFamily.Monospace
        }
        return null
    }

    private fun elementOf(t: MarkupToken.Start) = CssElement(
        name = t.name,
        id = t.attrs["id"],
        classes = t.attrs["class"].orEmpty().split(' ', '\t', '\n').filter { it.isNotEmpty() },
    )

    /** [t]'s declared CSS, presentational attributes (align, bgcolor, font color) below it. */
    private fun cssOf(t: MarkupToken.Start, path: List<CssElement>): Map<String, String> {
        val declared = sheet.declared(path, t.attrs["style"])
        val hints = buildMap {
            t.attrs["align"]?.let { put("text-align", it) }
            t.attrs["bgcolor"]?.let { put("background-color", it) }
            if (t.name == "font") t.attrs["color"]?.let { put("color", it) }
        }
        return if (hints.isEmpty()) declared else hints + declared
    }

    private fun open(path: String, t: MarkupToken.Start) {
        val name = t.name
        if (skipStack.isNotEmpty()) {
            if (name == "image" && "svg" in skipStack) {
                image(path, t.attrs["xlink:href"] ?: t.attrs["href"], t.attrs["alt"])
            } else if (!t.selfClosing && name !in VOID) {
                skipStack += name
            }
            return
        }
        if (name in SKIPPED) {
            if (!t.selfClosing) skipStack += name
            return
        }
        val element = elementOf(t)
        val elementPath = basePath + stack.map { it.element } + element
        val css = cssOf(t, elementPath)
        if (css["display"]?.trim()?.lowercase() == "none") {
            if (!t.selfClosing && name !in VOID) skipStack += name
            return
        }
        t.attrs["id"]?.let { pendingAnchors += "$path#$it" }
        when (name) {
            "img" -> {
                image(path, t.attrs["src"], t.attrs["alt"])
                return
            }
            "br" -> {
                if (headingOpen) headingText.append(' ')
                if (!breakPending && !blockEmpty) {
                    write("\n")
                    atBlockStart = true
                    spacePending = false
                }
                return
            }
            "hr" -> {
                holdBreak = false
                gap = maxOf(gap, 1f)
                block()
                centerNext = true
                startBlock()
                write("*   *   *")
                gap = maxOf(gap, 1f)
                block()
                return
            }
            "td", "th" -> spacePending = !atBlockStart
            "table" -> if (!inCell && !t.selfClosing) {
                tableTokens = ArrayList()
                tableDepth = 1
                tableStart = t
                tablePath = elementPath
                return
            }
        }
        if (t.selfClosing || name in VOID) return

        val level = HEADINGS.indexOf(name) + 1
        val heading = level > 0 && !headingOpen
        val display = css["display"]?.trim()?.lowercase()
        val isBlock = name in BLOCKS || display == "block" || display == "list-item"
        if (isBlock) {
            val inItem = holdBreak && name != "li"
            holdBreak = false
            if (!inItem) block()
            gap = maxOf(gap, margin(name, css, top = true))
        }
        when (name) {
            "ul" -> lists += intArrayOf(-1)
            "ol" -> lists += intArrayOf(t.attrs["start"]?.toIntOrNull() ?: 1)
            "li" -> {
                stack += Open(name, out.length, emptyList(), 0, true, css, element)
                startBlock()
                stack.removeAt(stack.lastIndex)
                val list = lists.lastOrNull()
                val marker = when {
                    list == null || list[0] < 0 -> "•"
                    else -> "${list[0]++}."
                }
                write("    ".repeat((lists.size - 1).coerceAtLeast(0)) + marker + " ")
                holdBreak = true
            }
            "pre" -> preDepth++
        }
        // A space before an inline element belongs before it, outside its styles.
        if (!isBlock && spacePending && !atBlockStart && !breakPending) {
            write(" ")
            spacePending = false
        }
        // A box draws its background behind its lines, so its text doesn't highlight it too.
        val box = isBlock && name != "body" && name != "html" && (hasBorder(css) || background(css) != null)
        // Font size: a heading inside a heading keeps the outer one's; the page's own size stays the reader's.
        val parentScale = stack.lastOrNull()?.scale ?: 1f
        val ownScale = when {
            name == "html" || name == "body" -> null
            level > 0 && !heading -> null
            else -> css["font-size"]?.let(::cssFontScale) ?: TAG_SCALE[name]
        }
        val scale = ownScale?.let { (parentScale * it).coerceIn(0.6f, 2.5f) } ?: parentScale
        val styles = buildList {
            if (!(level > 0 && !heading)) addAll(STYLES[name].orEmpty())
            cssSpan(css, background = !box, fontFamily = fontFamilyOf(css))?.let(::add)
            // As a factor of the reader's text size, not the parent's: the screen turns it into sp.
            if (scale != parentScale) add(SpanStyle(fontSize = scale.em))
        }
        if (heading) {
            // Break before the heading with it open, so the break takes its paragraph style, but start it after.
            stack += Open(name, out.length, emptyList(), level, true, css, element, scale, box)
            startBlock()
            stack.removeAt(stack.lastIndex)
            headingOpen = true
            headingText.clear()
        }
        stack += Open(name, out.length, styles, if (heading) level else 0, isBlock, css, element, scale, box)
    }

    private fun close(name: String) {
        if (skipStack.isNotEmpty()) {
            // Up to the matching start tag, so an unclosed <p> inside doesn't hide the rest.
            val at = skipStack.lastIndexOf(name)
            if (at >= 0) while (skipStack.size > at) skipStack.removeAt(skipStack.lastIndex)
            return
        }
        if (name in VOID) return
        // Close up to the matching element; unmatched end tags are ignored.
        if (stack.none { it.name == name }) return
        while (true) {
            val top = closeTop()
            if (top.name == name) break
        }
    }

    private fun closeTop(): Open {
        val top = stack.removeAt(stack.lastIndex)
        val end = out.length
        val start = if (top.contentStart >= 0) top.contentStart else top.start
        if (end > start) top.styles.forEach { spans += Span(it, start, end, stack.size) }
        if (top.heading > 0) {
            headingOpen = false
            val title = headingText.toString().collapseWhitespace()
            if (top.heading <= 3 && title.isNotEmpty() && end > top.start) {
                headings += Chapter(top.start, title, depth = top.heading - 1)
            }
        }
        when (top.name) {
            "ul", "ol" -> lists.removeLastOrNull()
            "pre" -> preDepth--
        }
        if (top.box) {
            if (end > start) {
                val left = stack.filter { it.block }.sumOf { leftOf(it).toDouble() }.toFloat() + marginLeftOf(top)
                boxes += BookBox(start, end, left.coerceIn(0f, 8f), background(top.css), hasBorder(top.css), borderColor(top.css))
            }
        }
        if (top.block) {
            gap = maxOf(gap, margin(top.name, top.css, top = false))
            block()
        }
        return top
    }

    /** [src] relative to the document at [doc]: the picture, else its alt text in its place. */
    private fun image(doc: String, src: String?, alt: String?) {
        val key = src?.takeUnless { it.startsWith("data:") }?.let { resolvePath(dirOf(doc), it.substringBefore('#')) }
        val image = key?.let { imageAt(it, alt.orEmpty()) }
        if (image == null) {
            alt?.let(::text)
            return
        }
        if (headingOpen) alt?.let { headingText.append(it) }
        if (image.block) {
            // Its own paragraph, unless it starts one already (a heading's, an li's after its marker).
            holdBreak = false
            if (!blockEmpty) {
                gap = maxOf(gap, 1f)
                block()
            }
            if (breakPending) startBlock()
        } else {
            if (breakPending) startBlock()
            resolveAnchors()
            if (spacePending && !atBlockStart) write(" ")
            spacePending = false
        }
        placeholder(IMAGE_TAG, key)
        if (image.block) {
            gap = maxOf(gap, 1f)
            block()
        } else {
            atBlockStart = false
            blockEmpty = false
            holdBreak = false
        }
    }

    private fun placeholder(tag: String, key: String) {
        out.pushStringAnnotation(tag, key)
        out.appendInlineContent(key, IMAGE_CHAR)
        out.pop()
        lastWritten = IMAGE_CHAR.last()
    }

    /**
     * A collected `<table>` ([start] and the tokens inside it) as a grid: rows
     * of cells, each cell's content built on its own, then one placeholder per
     * row. A caption goes above, centered.
     */
    private fun table(path: String, start: MarkupToken.Start, tokens: List<MarkupToken>) {
        val tableCss = cssOf(start, tablePath)
        var bordered = (start.attrs["border"]?.toIntOrNull() ?: 0) > 0 || hasBorder(tableCss)
        val rows = ArrayList<ArrayList<BookCell>>()
        var row: MarkupToken.Start? = null
        var rowCss: Map<String, String> = emptyMap()
        var cellStart: MarkupToken.Start? = null
        var cell: ArrayList<MarkupToken>? = null
        var caption: ArrayList<MarkupToken>? = null
        var captionDone: List<MarkupToken>? = null
        var nested = 0

        fun rowPath() = tablePath + (row?.let(::elementOf) ?: CssElement("tr", null, emptyList()))
        fun finishCell() {
            val s = cellStart ?: return
            val buf = cell ?: return
            val path0 = rowPath()
            val cssCell = cssOf(s, path0 + elementOf(s))
            if (hasBorder(cssCell)) bordered = true
            val text = BookBuilder(imageAt, sheetAt, fontAt, tables, boxes, inCell = true)
                .cell(path, sheet, faces, path0, listOf(s) + buf + MarkupToken.End(s.name))
            if (rows.isEmpty()) rows.add(ArrayList())
            rows.last() += BookCell(
                text = text,
                header = s.name == "th",
                span = (s.attrs["colspan"]?.toIntOrNull() ?: 1).coerceIn(1, 20),
                background = background(cssCell) ?: background(rowCss) ?: background(tableCss),
            )
            cellStart = null
            cell = null
        }
        for (t in tokens) {
            if (nested > 0) {
                if (t is MarkupToken.Start && t.name == "table" && !t.selfClosing) nested++
                if (t is MarkupToken.End && t.name == "table") nested--
                cell?.add(t) ?: caption?.add(t)
                continue
            }
            when {
                t is MarkupToken.Start && t.name == "table" && !t.selfClosing -> {
                    nested++
                    cell?.add(t) ?: caption?.add(t)
                }
                t is MarkupToken.Start && t.name == "tr" -> {
                    finishCell()
                    rows.add(ArrayList())
                    row = t
                    rowCss = cssOf(t, tablePath + elementOf(t))
                }
                t is MarkupToken.Start && (t.name == "td" || t.name == "th") -> {
                    finishCell()
                    cellStart = t
                    cell = ArrayList()
                    if (t.selfClosing) finishCell()
                }
                t is MarkupToken.End && (t.name == "td" || t.name == "th" || t.name == "tr") -> finishCell()
                t is MarkupToken.Start && t.name == "caption" && !t.selfClosing -> caption = ArrayList()
                t is MarkupToken.End && t.name == "caption" -> {
                    captionDone = caption
                    caption = null
                }
                else -> cell?.add(t) ?: caption?.add(t)
            }
        }
        finishCell()
        rows.removeAll { it.isEmpty() }

        (captionDone ?: caption)?.let { c ->
            open(path, MarkupToken.Start("caption", emptyMap(), selfClosing = false))
            run(path, c)
            close("caption")
        }
        if (rows.isEmpty()) return
        val index = tables.size
        tables += BookTable(rows, bordered)
        // Each row is a one-line paragraph of its own: Android sizes a paragraph's
        // middle lines from its first, which would crop a tall row to one line of text.
        holdBreak = false
        breakPending = false
        if (out.length > 0 && lastWritten != '\n') write("\n")
        closeRegion()
        resolveAnchors()
        rows.indices.forEach { r ->
            regionStyle = TABLE_ROW
            placeholder(TABLE_TAG, "$index:$r")
            closeRegion()
        }
        afterTable = true
        atBlockStart = true
        blockEmpty = false
        gap = maxOf(gap, 1f)
        block()
    }

    private fun text(s: String) {
        if (headingOpen) headingText.append(s)
        if (preDepth > 0) {
            if (s.isEmpty()) return
            startBlock()
            write(s.replace("\r\n", "\n"))
            atBlockStart = s.endsWith('\n')
            blockEmpty = false
            return
        }
        val sb = StringBuilder()
        for (c in s) {
            if (c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == '\u000C') {
                spacePending = true
                continue
            }
            if (sb.isEmpty()) {
                if (breakPending) startBlock()
                resolveAnchors()
                if (spacePending && !atBlockStart) sb.append(' ')
            } else if (spacePending) {
                sb.append(' ')
            }
            spacePending = false
            sb.append(c)
        }
        if (sb.isNotEmpty()) {
            write(sb.toString())
            atBlockStart = false
            blockEmpty = false
            holdBreak = false
        }
    }

    private fun block() {
        if (holdBreak) return
        breakPending = true
        spacePending = false
        atBlockStart = true
    }

    /**
     * Writes a pending paragraph break now: a blank line, or just a line
     * break where the book set no margin, none at the very start. A block
     * styled differently from the last one starts a new region.
     */
    private fun startBlock() {
        // Elements opened since the last character start after the break, not before it.
        val atStart = stack.filter { it.contentStart < 0 && (it.block || it.start == out.length) }
        if (breakPending) {
            val style = paragraphStyle()
            val blank = gap > 0.05f
            if (out.length == 0) {
                regionStyle = style
            } else if (afterTable) {
                closeRegion()
                regionStyle = style
                if (blank) write("\n")
            } else if (style == regionStyle) {
                write(
                    when {
                        lastWritten == '\n' -> if (blank) "\n" else ""
                        else -> if (blank) "\n\n" else "\n"
                    },
                )
            } else {
                // The region's end is a line break already; its trailing newline makes the blank line.
                if (blank && lastWritten != '\n') write("\n")
                closeRegion()
                regionStyle = style
            }
        }
        breakPending = false
        spacePending = false
        atBlockStart = true
        blockEmpty = true
        gap = 0f
        afterTable = false
        for (o in atStart) o.contentStart = out.length
        resolveAnchors()
    }

    private fun closeRegion() {
        val style = regionStyle
        if (style != null && out.length > regionStart) out.addStyle(style, regionStart, out.length)
        regionStart = out.length
    }

    /**
     * The open blocks' alignment, indent and left margin (and a heading's
     * line height) as one paragraph style; null when the reader's own
     * defaults apply. Left / justified text is left to the reader, which
     * justifies two-page spreads.
     */
    private fun paragraphStyle(): ParagraphStyle? {
        val center = centerNext
        centerNext = false
        val align = if (center) TextAlign.Center else stack.asReversed().firstNotNullOfOrNull { o ->
            o.css["text-align"]?.let(::alignOf) ?: DEFAULT_ALIGN[o.name]
        }?.takeUnless { it == TextAlign.Unspecified }
        val indent = (stack.asReversed().firstNotNullOfOrNull { it.css["text-indent"]?.let(::cssEm) } ?: 0f).coerceIn(-4f, 4f)
        val left = stack.filter { it.block }.sumOf { leftOf(it).toDouble() }.toFloat().coerceIn(0f, 8f)
        val heading = stack.lastOrNull { it.heading > 0 }?.heading ?: 0
        // Large type (a heading, a book's display text) needs taller lines than the reader's spacing.
        val scale = stack.lastOrNull { it.block }?.scale ?: 1f
        if (align == null && indent == 0f && left == 0f && heading == 0 && scale <= 1.1f) return null
        return ParagraphStyle(
            textAlign = align ?: TextAlign.Unspecified,
            textIndent = if (indent != 0f || left != 0f) TextIndent((left + indent).coerceAtLeast(0f).em, left.em) else null,
            lineHeight = if (heading > 0 || scale > 1.1f) (scale * 1.3f).em else TextUnit.Unspecified,
        )
    }

    private fun write(s: String) {
        if (s.isEmpty()) return
        out.append(s)
        lastWritten = s.last()
    }

    private fun resolveAnchors() {
        if (pendingAnchors.isEmpty()) return
        val at = out.length
        pendingAnchors.forEach { anchors.getOrPut(it) { at } }
        pendingAnchors.clear()
    }

    companion object {
        val SKIPPED = setOf("head", "script", "style", "svg", "math", "template", "title")
        val VOID = setOf("area", "base", "col", "embed", "img", "image", "input", "link", "meta", "param", "source", "track", "wbr", "br", "hr")
        val HEADINGS = listOf("h1", "h2", "h3", "h4", "h5", "h6")
        val BLOCKS = setOf(
            "p", "div", "section", "article", "header", "footer", "aside", "nav", "main", "figure", "figcaption",
            "blockquote", "pre", "ul", "ol", "li", "dl", "dt", "dd", "table", "tr", "caption", "address", "center",
            "h1", "h2", "h3", "h4", "h5", "h6", "body",
        )
        val DEFAULT_ALIGN = mapOf(
            "h1" to TextAlign.Center, "h2" to TextAlign.Center, "h3" to TextAlign.Center,
            "h4" to TextAlign.Center, "h5" to TextAlign.Center, "h6" to TextAlign.Center,
            "figcaption" to TextAlign.Center, "caption" to TextAlign.Center, "center" to TextAlign.Center,
            "th" to TextAlign.Center,
        )
        val DEFAULT_INDENT = mapOf("blockquote" to 1.5f, "dd" to 1.5f)
        // Space between a box's edge and its text, in ems.
        const val BOX_PADDING = 0.6f
        // Default sizes, as in a browser's stylesheet.
        val TAG_SCALE = mapOf(
            "h1" to 1.5f, "h2" to 1.3f, "h3" to 1.15f,
            "sup" to 0.75f, "sub" to 0.75f, "small" to 0.85f, "figcaption" to 0.85f, "pre" to 0.85f,
        )

        private fun marginLeftOf(o: Open): Float =
            o.css["margin-left"]?.let(::cssEm) ?: o.css["margin"]?.let(::cssBox)?.get(3)?.let(::cssEm) ?: DEFAULT_INDENT[o.name] ?: 0f

        /** How far a block moves its text in: margin, padding, and a box's inner space. */
        private fun leftOf(o: Open): Float {
            val padding = o.css["padding-left"]?.let(::cssEm) ?: o.css["padding"]?.let(::cssBox)?.get(3)?.let(::cssEm) ?: 0f
            return marginLeftOf(o) + padding + if (o.box) BOX_PADDING else 0f
        }

        /** The first colour any `border*` property names, if any. */
        fun borderColor(css: Map<String, String>): Color? = css.entries.firstNotNullOfOrNull { (k, v) ->
            if (k.startsWith("border") && !k.contains("collapse") && !k.contains("spacing") && !k.contains("radius")) cssBackground(v) else null
        }

        /** A table row's paragraph: unlike any style a block gets, so rows never join a text region. */
        val TABLE_ROW = ParagraphStyle(
            textAlign = TextAlign.Center,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both, LineHeightStyle.Mode.Fixed),
        )

        /**
         * A block's top or bottom margin, in ems: what the book's CSS says, else
         * a blank line for HTML's block elements and none for an element the
         * CSS made a block (a poem's line, say).
         */
        fun margin(name: String, css: Map<String, String>, top: Boolean): Float {
            val side = css[if (top) "margin-top" else "margin-bottom"]
                ?: css["margin"]?.let(::cssBox)?.get(if (top) 0 else 2)
            return side?.let(::cssEm) ?: if (name in BLOCKS) 1f else 0f
        }

        fun alignOf(value: String): TextAlign? = when (value.trim().lowercase()) {
            "center", "middle" -> TextAlign.Center
            "right", "end" -> TextAlign.End
            // The reader's own: left, start, justify.
            "left", "start", "justify" -> TextAlign.Unspecified
            else -> null
        }

        fun hasBorder(css: Map<String, String>): Boolean = css.any { (k, v) ->
            k.startsWith("border") && !k.contains("collapse") && !k.contains("spacing") &&
                v.trim().lowercase().let { it != "none" && it != "0" && it != "hidden" && !it.startsWith("0 ") }
        }

        fun background(css: Map<String, String>): Color? =
            (css["background-color"] ?: css["background"])?.let(::cssBackground)?.takeIf { it.alpha > 0f }

        /** The book's colours, weight, style, decoration and small caps for an element. */
        fun cssSpan(css: Map<String, String>, background: Boolean = true, fontFamily: FontFamily? = null): SpanStyle? {
            val color = css["color"]?.let(::cssColor)
            val background = if (background) background(css) else null
            val fontStyle = when (css["font-style"]?.trim()?.lowercase()) {
                "italic", "oblique" -> FontStyle.Italic
                "normal" -> FontStyle.Normal
                else -> null
            }
            val weight = css["font-weight"]?.trim()?.lowercase()?.let { w ->
                when {
                    w == "bold" || w == "bolder" -> FontWeight.Bold
                    w == "normal" || w == "lighter" -> FontWeight.Normal
                    else -> w.toIntOrNull()?.takeIf { it in 100..900 }?.let { FontWeight(it) }
                }
            }
            val decoration = (css["text-decoration-line"] ?: css["text-decoration"])?.lowercase()?.let { d ->
                when {
                    "none" in d -> TextDecoration.None
                    "underline" in d && "line-through" in d -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                    "underline" in d -> TextDecoration.Underline
                    "line-through" in d -> TextDecoration.LineThrough
                    else -> null
                }
            }
            val smallCaps = (css["font-variant"] ?: css["font-variant-caps"])?.contains("small-caps") == true
            if (color == null && background == null && fontStyle == null && weight == null && decoration == null && !smallCaps && fontFamily == null) return null
            return SpanStyle(
                color = color ?: Color.Unspecified,
                background = background ?: Color.Unspecified,
                fontStyle = fontStyle,
                fontWeight = weight,
                textDecoration = decoration,
                fontFeatureSettings = if (smallCaps) "smcp" else null,
                fontFamily = fontFamily,
            )
        }

        private val italic = SpanStyle(fontStyle = FontStyle.Italic)
        private val bold = SpanStyle(fontWeight = FontWeight.Bold)
        private val heading = listOf(SpanStyle(fontWeight = FontWeight.SemiBold))

        val STYLES: Map<String, List<SpanStyle>> = mapOf(
            "em" to listOf(italic), "i" to listOf(italic), "cite" to listOf(italic), "dfn" to listOf(italic), "var" to listOf(italic),
            "strong" to listOf(bold), "b" to listOf(bold), "dt" to listOf(bold), "th" to listOf(bold),
            "u" to listOf(SpanStyle(textDecoration = TextDecoration.Underline)),
            "ins" to listOf(SpanStyle(textDecoration = TextDecoration.Underline)),
            "s" to listOf(SpanStyle(textDecoration = TextDecoration.LineThrough)),
            "strike" to listOf(SpanStyle(textDecoration = TextDecoration.LineThrough)),
            "del" to listOf(SpanStyle(textDecoration = TextDecoration.LineThrough)),
            "mark" to listOf(SpanStyle(background = Color(0xFFFFF176))),
            "sup" to listOf(SpanStyle(baselineShift = BaselineShift.Superscript)),
            "sub" to listOf(SpanStyle(baselineShift = BaselineShift.Subscript)),
            "code" to listOf(SpanStyle(fontFamily = FontFamily.Monospace)),
            "tt" to listOf(SpanStyle(fontFamily = FontFamily.Monospace)),
            "kbd" to listOf(SpanStyle(fontFamily = FontFamily.Monospace)),
            "samp" to listOf(SpanStyle(fontFamily = FontFamily.Monospace)),
            "pre" to listOf(SpanStyle(fontFamily = FontFamily.Monospace)),
            "h1" to heading, "h2" to heading, "h3" to heading, "h4" to heading, "h5" to heading, "h6" to heading,
        )
    }
}

private fun String.collapseWhitespace(): String = trim().replace(Regex("\\s+"), " ")

/** "OEBPS/Text/ch1.xhtml" → "OEBPS/Text/"; a root-level file → "". */
private fun dirOf(path: String): String = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

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
