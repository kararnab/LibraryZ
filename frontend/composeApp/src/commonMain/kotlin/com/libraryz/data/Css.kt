package com.libraryz.data

import androidx.compose.ui.graphics.Color

/**
 * The slice of CSS the EPUB reader honours: rules from a book's stylesheets
 * and `<style>` blocks, matched by tag, `.class`, `#id`, `*` and the
 * descendant and child combinators. Anything else (pseudo-classes, attribute
 * and sibling selectors, `@media` and other at-rules) is dropped rather than
 * guessed at. Declarations stay strings; the reader parses only the
 * properties it uses.
 */
internal class CssRule(
    private val selector: List<CssCompound>,
    /** Combinators between [selector]'s parts, left to right: ' ' or '>'. */
    private val combinators: List<Char>,
    val specificity: Int,
    val order: Int,
    val declarations: List<CssDeclaration>,
) {
    /** Whether the last element of [path] (its ancestors before it) is selected. */
    fun matches(path: List<CssElement>): Boolean {
        var at = path.lastIndex
        if (at < 0 || !selector.last().matches(path[at])) return false
        for (i in selector.lastIndex - 1 downTo 0) {
            val part = selector[i]
            if (combinators[i] == '>') {
                at--
                if (at < 0 || !part.matches(path[at])) return false
            } else {
                do at-- while (at >= 0 && !part.matches(path[at]))
                if (at < 0) return false
            }
        }
        return true
    }
}

internal class CssDeclaration(val property: String, val value: String, val important: Boolean)

/** An element as selectors see it. */
internal class CssElement(val name: String, val id: String?, val classes: List<String>)

internal class CssCompound(private val tag: String?, private val id: String?, private val classes: List<String>) {
    fun matches(e: CssElement): Boolean =
        (tag == null || tag == e.name) && (id == null || id == e.id) && classes.all { it in e.classes }

    val specificity: Int get() = (if (id != null) 10_000 else 0) + classes.size * 100 + (if (tag != null) 1 else 0)
}

/** The rules that apply to a document: its stylesheets ([sources]) in the order it links them. */
internal class Stylesheet(sources: List<List<CssRule>>) {
    private val rules: List<Pair<Int, CssRule>> = sources.flatMapIndexed { i, rules -> rules.map { i to it } }

    /**
     * The declared properties of [path]'s last element: matching rules by
     * specificity then source order, `!important` above the rest, and
     * [inline] (its `style` attribute) above every rule that isn't important.
     */
    fun declared(path: List<CssElement>, inline: String?): Map<String, String> {
        val out = HashMap<String, String>()
        val inlineDecls = inline?.let(::parseDeclarations).orEmpty()
        if (rules.isEmpty() && inlineDecls.isEmpty()) return out
        val matching = rules.filter { it.second.matches(path) }
            .sortedWith(compareBy({ it.second.specificity }, { it.first }, { it.second.order }))
            .map { it.second }
        for (important in listOf(false, true)) {
            for (r in matching) for (d in r.declarations) if (d.important == important) out[d.property] = d.value
            for (d in inlineDecls) if (d.important == important) out[d.property] = d.value
        }
        return out
    }

    companion object {
        val EMPTY = Stylesheet(emptyList())
    }
}

/** [css]'s rules, numbered from [firstOrder] so later sheets win ties. */
internal fun parseCss(css: String, firstOrder: Int = 0): List<CssRule> {
    val src = css.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
    val rules = ArrayList<CssRule>()
    var i = 0
    var order = firstOrder
    while (i < src.length) {
        while (i < src.length && (src[i].isWhitespace() || src[i] == ';' || src[i] == '}')) i++
        if (i >= src.length) break
        if (src[i] == '@') {
            // At-rules: a statement up to ';', or a block skipped whole.
            val semi = src.indexOf(';', i).let { if (it < 0) src.length else it }
            val brace = src.indexOf('{', i).let { if (it < 0) src.length else it }
            i = if (semi < brace) semi + 1 else skipBlock(src, brace)
            continue
        }
        val open = src.indexOf('{', i)
        if (open < 0) break
        val close = src.indexOf('}', open).let { if (it < 0) src.length else it }
        val declarations = parseDeclarations(src.substring(open + 1, close))
        for (s in src.substring(i, open).split(',')) {
            parseSelector(s.trim())?.let { (parts, combinators) ->
                rules += CssRule(parts, combinators, parts.sumOf { it.specificity }, order++, declarations)
            }
        }
        i = close + 1
    }
    return rules
}

/** The index after the `{…}` block opening at [open], nested blocks included. */
private fun skipBlock(src: String, open: Int): Int {
    var depth = 0
    var i = open
    while (i < src.length) {
        when (src[i]) {
            '{' -> depth++
            '}' -> if (--depth == 0) return i + 1
        }
        i++
    }
    return src.length
}

internal fun parseDeclarations(block: String): List<CssDeclaration> = block.split(';').mapNotNull { d ->
    val colon = d.indexOf(':')
    if (colon <= 0) return@mapNotNull null
    var value = d.substring(colon + 1).trim()
    val important = value.endsWith("!important", ignoreCase = true)
    if (important) value = value.dropLast("!important".length).trim()
    CssDeclaration(d.substring(0, colon).trim().lowercase(), value, important).takeIf { value.isNotEmpty() }
}

private val COMPOUND = Regex("^([a-zA-Z][a-zA-Z0-9]*|\\*)?((?:[.#][a-zA-Z_\\-][\\w\\-]*)*)$")

/** A selector's compounds and combinators, or null if it uses anything unsupported. */
private fun parseSelector(s: String): Pair<List<CssCompound>, List<Char>>? {
    if (s.isEmpty()) return null
    val parts = ArrayList<CssCompound>()
    val combinators = ArrayList<Char>()
    var pending = ' '
    for (token in s.replace(">", " > ").split(Regex("\\s+")).filter { it.isNotEmpty() }) {
        if (token == ">") {
            if (parts.isEmpty()) return null
            pending = '>'
            continue
        }
        val m = COMPOUND.matchEntire(token) ?: return null
        val tag = m.groupValues[1].lowercase().takeUnless { it.isEmpty() || it == "*" }
        val rest = m.groupValues[2]
        val id = Regex("#([\\w\\-]+)").find(rest)?.groupValues?.get(1)
        val classes = Regex("\\.([\\w\\-]+)").findAll(rest).map { it.groupValues[1] }.toList()
        if (parts.isNotEmpty()) combinators += pending
        pending = ' '
        parts += CssCompound(tag, id, classes)
    }
    return if (parts.isEmpty() || pending == '>') null else parts to combinators
}

/** A CSS length as ems, for indents and margins: em/rem, px (16 to an em), pt (12), % of a ~30 em column. */
internal fun cssEm(value: String): Float? {
    val v = value.trim().lowercase()
    if (v == "0") return 0f
    val m = Regex("^(-?[0-9]*\\.?[0-9]+)(em|rem|px|pt|%|ex|ch)$").matchEntire(v) ?: return null
    val n = m.groupValues[1].toFloatOrNull() ?: return null
    return when (m.groupValues[2]) {
        "em", "rem" -> n
        "px" -> n / 16f
        "pt" -> n / 12f
        "%" -> n * 0.3f
        else -> n / 2f // ex, ch
    }
}

/** The four sides of a `margin` / `padding` shorthand: top, right, bottom, left. */
internal fun cssBox(value: String): List<String>? {
    val v = value.trim().split(Regex("\\s+"))
    return when (v.size) {
        1 -> listOf(v[0], v[0], v[0], v[0])
        2 -> listOf(v[0], v[1], v[0], v[1])
        3 -> listOf(v[0], v[1], v[2], v[1])
        4 -> v
        else -> null
    }
}

/** `#rgb`, `#rrggbb`, `#rrggbbaa`, `rgb()` / `rgba()` or a common colour name; null for anything else. */
internal fun cssColor(value: String): Color? {
    val v = value.trim().lowercase()
    if (v.startsWith("#")) {
        val hex = v.drop(1)
        val full = when (hex.length) {
            3, 4 -> hex.map { "$it$it" }.joinToString("")
            6, 8 -> hex
            else -> return null
        }
        val n = full.toLongOrNull(16) ?: return null
        return if (full.length == 6) Color(0xFF000000 or n) else Color(((n and 0xFF) shl 24) or (n ushr 8))
    }
    Regex("^rgba?\\(([^)]*)\\)$").matchEntire(v)?.let { m ->
        val parts = m.groupValues[1].split(Regex("[,/\\s]+")).filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        fun channel(s: String) = if (s.endsWith("%")) s.dropLast(1).toFloatOrNull()?.div(100f) else s.toFloatOrNull()?.div(255f)
        val (r, g, b) = parts.take(3).map { channel(it)?.coerceIn(0f, 1f) ?: return null }
        val a = parts.getOrNull(3)?.let { if (it.endsWith("%")) it.dropLast(1).toFloatOrNull()?.div(100f) else it.toFloatOrNull() } ?: 1f
        return Color(r, g, b, a.coerceIn(0f, 1f))
    }
    return CSS_COLORS[v]?.let { Color(0xFF000000 or it.toLong()) }
}

/** The first colour among a `background` shorthand's words. */
internal fun cssBackground(value: String): Color? =
    cssColor(value) ?: value.split(Regex("\\s+(?![^(]*\\))")).firstNotNullOfOrNull { cssColor(it) }

private val CSS_COLORS = mapOf(
    "black" to 0x000000, "white" to 0xFFFFFF, "gray" to 0x808080, "grey" to 0x808080, "silver" to 0xC0C0C0,
    "darkgray" to 0xA9A9A9, "darkgrey" to 0xA9A9A9, "dimgray" to 0x696969, "dimgrey" to 0x696969,
    "lightgray" to 0xD3D3D3, "lightgrey" to 0xD3D3D3, "gainsboro" to 0xDCDCDC, "whitesmoke" to 0xF5F5F5,
    "red" to 0xFF0000, "darkred" to 0x8B0000, "maroon" to 0x800000, "crimson" to 0xDC143C, "firebrick" to 0xB22222,
    "indianred" to 0xCD5C5C, "salmon" to 0xFA8072, "lightsalmon" to 0xFFA07A, "tomato" to 0xFF6347, "coral" to 0xFF7F50,
    "orangered" to 0xFF4500, "orange" to 0xFFA500, "darkorange" to 0xFF8C00, "gold" to 0xFFD700, "yellow" to 0xFFFF00,
    "lightyellow" to 0xFFFFE0, "khaki" to 0xF0E68C, "goldenrod" to 0xDAA520, "darkgoldenrod" to 0xB8860B,
    "olive" to 0x808000, "lime" to 0x00FF00, "limegreen" to 0x32CD32, "green" to 0x008000, "darkgreen" to 0x006400,
    "forestgreen" to 0x228B22, "seagreen" to 0x2E8B57, "lightgreen" to 0x90EE90, "palegreen" to 0x98FB98,
    "olivedrab" to 0x6B8E23, "teal" to 0x008080, "darkcyan" to 0x008B8B, "cyan" to 0x00FFFF, "aqua" to 0x00FFFF,
    "lightcyan" to 0xE0FFFF, "turquoise" to 0x40E0D0, "aquamarine" to 0x7FFFD4, "blue" to 0x0000FF, "navy" to 0x000080,
    "darkblue" to 0x00008B, "mediumblue" to 0x0000CD, "royalblue" to 0x4169E1, "steelblue" to 0x4682B4,
    "dodgerblue" to 0x1E90FF, "deepskyblue" to 0x00BFFF, "skyblue" to 0x87CEEB, "lightblue" to 0xADD8E6,
    "cornflowerblue" to 0x6495ED, "slateblue" to 0x6A5ACD, "indigo" to 0x4B0082, "purple" to 0x800080,
    "darkviolet" to 0x9400D3, "violet" to 0xEE82EE, "orchid" to 0xDA70D6, "plum" to 0xDDA0DD, "lavender" to 0xE6E6FA,
    "magenta" to 0xFF00FF, "fuchsia" to 0xFF00FF, "deeppink" to 0xFF1493, "hotpink" to 0xFF69B4, "pink" to 0xFFC0CB,
    "lightpink" to 0xFFB6C1, "brown" to 0xA52A2A, "sienna" to 0xA0522D, "saddlebrown" to 0x8B4513,
    "chocolate" to 0xD2691E, "peru" to 0xCD853F, "tan" to 0xD2B48C, "wheat" to 0xF5DEB3, "beige" to 0xF5F5DC,
    "ivory" to 0xFFFFF0, "linen" to 0xFAF0E6, "mistyrose" to 0xFFE4E1, "honeydew" to 0xF0FFF0, "azure" to 0xF0FFFF,
    "aliceblue" to 0xF0F8FF, "slategray" to 0x708090, "slategrey" to 0x708090, "lightslategray" to 0x778899,
)

/** An `@font-face` rule: [family] (lowercased, unquoted) is drawn with the font file at [src], resolved against the sheet. */
internal class CssFontFace(val family: String, val src: String, val weight: Int, val italic: Boolean)

/** [css]'s `@font-face` rules that name a font file (`src: url(…)`), local fonts and data URLs aside. */
internal fun parseFontFaces(css: String): List<CssFontFace> {
    val src = css.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
    return Regex("@font-face\\s*\\{([^}]*)\\}", RegexOption.IGNORE_CASE).findAll(src).mapNotNull { m ->
        val d = parseDeclarations(m.groupValues[1]).associate { it.property to it.value }
        val family = d["font-family"]?.let(::unquote)?.lowercase() ?: return@mapNotNull null
        val url = d["src"]?.let { Regex("url\\(\\s*([^)]*?)\\s*\\)").find(it)?.groupValues?.get(1) }?.let(::unquote)
            ?.takeUnless { it.startsWith("data:") || it.isEmpty() } ?: return@mapNotNull null
        val weight = when (val w = d["font-weight"]?.trim()?.lowercase()) {
            null, "normal" -> 400
            "bold" -> 700
            else -> w.split(Regex("\\s+")).first().toIntOrNull()?.coerceIn(100, 900) ?: 400
        }
        CssFontFace(family, url, weight, italic = d["font-style"]?.trim()?.lowercase().let { it == "italic" || it == "oblique" })
    }.toList()
}

/** The families a `font-family` value lists, in order, lowercased and unquoted. */
internal fun cssFontFamilies(value: String): List<String> =
    value.split(',').map { unquote(it).lowercase() }.filter { it.isNotEmpty() }

private fun unquote(s: String): String = s.trim().removeSurrounding("\"").removeSurrounding("'").trim()

/**
 * A CSS `font-size` relative to the parent's, as a factor: em, %, rem, ex,
 * smaller / larger, the absolute keywords (medium = 1), and px / pt against a
 * 16 px default. Null for anything else.
 */
internal fun cssFontScale(value: String): Float? {
    val v = value.trim().lowercase()
    return when (v) {
        "xx-small" -> 0.6f
        "x-small" -> 0.75f
        "small", "smaller" -> 0.89f
        "medium", "inherit", "initial" -> 1f
        "large", "larger" -> 1.2f
        "x-large" -> 1.5f
        "xx-large" -> 2f
        "xxx-large" -> 3f
        else -> {
            val m = Regex("^([0-9]*\\.?[0-9]+)(em|rem|%|px|pt|ex)$").matchEntire(v) ?: return null
            val n = m.groupValues[1].toFloatOrNull() ?: return null
            when (m.groupValues[2]) {
                "em", "rem" -> n
                "%" -> n / 100f
                "px" -> n / 16f
                "pt" -> n / 12f
                else -> n / 2f
            }
        }
    }
}
