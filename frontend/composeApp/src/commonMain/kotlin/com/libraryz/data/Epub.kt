package com.libraryz.data

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em

/** An EPUB's reading text and table of contents, ready for [TextReader]. */
class EpubBook(val text: AnnotatedString, val chapters: List<Chapter>)

class EpubException(message: String) : RuntimeException(message)

/**
 * Reads an EPUB (2 or 3) as reflowable text: the spine's XHTML documents in
 * order, one after another, keeping headings, emphasis, lists and paragraph
 * breaks and dropping the book's CSS, images and layout. The reader's own
 * typography and themes then apply, as they do to TXT.
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
        for (t in markupTokens(opf)) {
            if (t !is MarkupToken.Start) continue
            when (t.name) {
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

        val book = BookBuilder()
        for (id in spine) {
            val item = items[id] ?: continue
            if (!item.isDocument) continue
            val xhtml = zip.readText(item.path) ?: continue
            book.addDocument(item.path, xhtml)
        }
        val text = book.build()
        if (text.text.isBlank()) throw EpubException("This EPUB has no text to show (it may be all images).")

        val toc = tableOfContents(zip, items, ncxId)
            .mapNotNull { e -> book.offsetOf(e.path, e.fragment)?.let { Chapter(it, e.title, e.depth) } }
            .distinct()
            .sortedBy { it.offset }
        return EpubBook(text, toc.ifEmpty { book.headings })
    } catch (e: ZipException) {
        throw EpubException("This EPUB is damaged (${e.message}).")
    }
}

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
 * Turns XHTML into one [AnnotatedString]: blocks separated by a blank line
 * (as TXT paragraphs are), whitespace collapsed as a browser would, inline
 * styles kept as spans. Remembers where every document and `id` landed so
 * the table of contents can point into the text.
 */
private class BookBuilder {
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
    private var skipDepth = 0
    private var preDepth = 0
    private var lastWritten: Char? = null

    private class Open(val name: String, val start: Int, val styles: List<Any>, val heading: Int)
    private val stack = ArrayList<Open>()
    private val lists = ArrayList<IntArray>() // [next number], or [-1] for a bulleted list
    private var headingOpen = false
    private val headingText = StringBuilder()

    fun offsetOf(path: String, fragment: String): Int? =
        (if (fragment.isNotEmpty()) anchors["$path#$fragment"] else null) ?: anchors[path]

    fun addDocument(path: String, xhtml: String) {
        block()
        pendingAnchors += path
        for (t in markupTokens(xhtml)) {
            when (t) {
                is MarkupToken.Start -> open(path, t)
                is MarkupToken.End -> close(t.name)
                is MarkupToken.Text -> if (skipDepth == 0) text(t.text)
            }
        }
        while (stack.isNotEmpty()) closeTop()
        skipDepth = 0
        preDepth = 0
        lists.clear()
        resolveAnchors()
    }

    fun build(): AnnotatedString = out.toAnnotatedString()

    private fun open(path: String, t: MarkupToken.Start) {
        val name = t.name
        if (name in SKIPPED) {
            if (!t.selfClosing) skipDepth++
            return
        }
        if (skipDepth > 0) return
        t.attrs["id"]?.let { pendingAnchors += "$path#$it" }
        when (name) {
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
                block()
                startBlock()
                val start = out.length
                write("*   *   *")
                if (!headingOpen) out.addStyle(CENTERED, start, out.length)
                block()
                return
            }
            "td", "th" -> spacePending = !atBlockStart
        }
        if (t.selfClosing || name in VOID) return

        val level = HEADINGS.indexOf(name) + 1
        val heading = level > 0 && !headingOpen
        if (name in BLOCKS) {
            val inItem = holdBreak && name != "li"
            holdBreak = false
            if (!inItem) block()
        }
        when (name) {
            "ul" -> lists += intArrayOf(-1)
            "ol" -> lists += intArrayOf(t.attrs["start"]?.toIntOrNull() ?: 1)
            "li" -> {
                startBlock()
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
        if (name !in BLOCKS && spacePending && !atBlockStart && !breakPending) {
            write(" ")
            spacePending = false
        }
        if (heading) {
            startBlock()
            headingOpen = true
            headingText.clear()
        }
        // A heading inside a heading keeps only the outer one's styles: paragraph styles can't overlap.
        val styles = if (level > 0 && !heading) emptyList() else STYLES[name].orEmpty()
        stack += Open(name, out.length, styles, if (heading) level else 0)
    }

    private fun close(name: String) {
        if (name in SKIPPED) {
            if (skipDepth > 0) skipDepth--
            return
        }
        if (skipDepth > 0 || name in VOID) return
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
        if (end > top.start) top.styles.forEach { style ->
            when (style) {
                is SpanStyle -> out.addStyle(style, top.start, end)
                is ParagraphStyle -> out.addStyle(style, top.start, end)
            }
        }
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
        if (top.name in BLOCKS) block()
        return top
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

    /** Writes a pending paragraph break now: a blank line between blocks, none at the very start. */
    private fun startBlock() {
        if (breakPending && out.length > 0) write(if (lastWritten == '\n') "\n" else "\n\n")
        breakPending = false
        spacePending = false
        atBlockStart = true
        blockEmpty = true
        resolveAnchors()
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
        val VOID = setOf("area", "base", "col", "embed", "img", "image", "input", "link", "meta", "param", "source", "track", "wbr")
        val HEADINGS = listOf("h1", "h2", "h3", "h4", "h5", "h6")
        val BLOCKS = setOf(
            "p", "div", "section", "article", "header", "footer", "aside", "nav", "main", "figure", "figcaption",
            "blockquote", "pre", "ul", "ol", "li", "dl", "dt", "dd", "table", "tr", "caption", "address",
            "h1", "h2", "h3", "h4", "h5", "h6", "body",
        )
        val CENTERED = ParagraphStyle(textAlign = TextAlign.Center)

        private val italic = SpanStyle(fontStyle = FontStyle.Italic)
        private val bold = SpanStyle(fontWeight = FontWeight.Bold)
        private fun heading(size: Float): List<Any> = listOf(
            SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = size.em),
            ParagraphStyle(textAlign = TextAlign.Center, lineHeight = (size * 1.3f).em),
        )

        val STYLES: Map<String, List<Any>> = mapOf(
            "em" to listOf(italic), "i" to listOf(italic), "cite" to listOf(italic), "dfn" to listOf(italic), "var" to listOf(italic),
            "strong" to listOf(bold), "b" to listOf(bold), "dt" to listOf(bold), "th" to listOf(bold),
            "u" to listOf(SpanStyle(textDecoration = TextDecoration.Underline)),
            "s" to listOf(SpanStyle(textDecoration = TextDecoration.LineThrough)),
            "strike" to listOf(SpanStyle(textDecoration = TextDecoration.LineThrough)),
            "del" to listOf(SpanStyle(textDecoration = TextDecoration.LineThrough)),
            "sup" to listOf(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.75.em)),
            "sub" to listOf(SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.75.em)),
            "small" to listOf(SpanStyle(fontSize = 0.85.em)),
            "code" to listOf(SpanStyle(fontFamily = FontFamily.Monospace)),
            "tt" to listOf(SpanStyle(fontFamily = FontFamily.Monospace)),
            "kbd" to listOf(SpanStyle(fontFamily = FontFamily.Monospace)),
            "samp" to listOf(SpanStyle(fontFamily = FontFamily.Monospace)),
            "pre" to listOf(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.85.em)),
            "h1" to heading(1.5f), "h2" to heading(1.3f), "h3" to heading(1.15f),
            "h4" to heading(1f), "h5" to heading(1f), "h6" to heading(1f),
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
