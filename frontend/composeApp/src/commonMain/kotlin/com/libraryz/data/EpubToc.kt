package com.libraryz.data

internal class ManifestItem(val path: String, val type: String, val properties: List<String>) {
    val isDocument: Boolean
        get() = type == "application/xhtml+xml" || type == "text/html" ||
            (type.isEmpty() && (path.endsWith(".xhtml", true) || path.endsWith(".html", true) || path.endsWith(".htm", true)))
}

internal class TocEntry(val title: String, val path: String, val fragment: String, val depth: Int)

internal fun tableOfContents(zip: ZipArchive, items: Map<String, ManifestItem>, ncxId: String?): List<TocEntry> {
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
