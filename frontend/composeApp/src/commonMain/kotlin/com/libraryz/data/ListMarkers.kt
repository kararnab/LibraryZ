package com.libraryz.data

/**
 * An open `<ol>`/`<ul>`: the number its next item gets, and which way it
 * counts. Every list counts (`ul { list-style: decimal }` numbers its
 * items), whether or not its markers show the number.
 */
internal class ListCounter(var next: Int, val step: Int, val ordered: Boolean)

private val BULLETS = mapOf("disc" to "•", "circle" to "◦", "square" to "▪")
private val NUMBERED = setOf(
    "decimal", "decimal-leading-zero", "lower-alpha", "lower-latin", "upper-alpha", "upper-latin",
    "lower-roman", "upper-roman", "lower-greek",
)

/**
 * The `list-style-type` in [css] (its own property, else the type keyword in
 * the `list-style` shorthand), or null if neither names one. A quoted string
 * comes back with its quotes, as CSS writes it.
 */
internal fun listStyleType(css: Map<String, String>): String? {
    css["list-style-type"]?.trim()?.takeIf { it.isNotEmpty() && it != "inherit" }?.let {
        return if (it.startsWith('"') || it.startsWith('\'')) it else it.lowercase()
    }
    val shorthand = css["list-style"]?.trim() ?: return null
    if (shorthand.startsWith('"') || shorthand.startsWith('\'')) {
        val close = shorthand.indexOf(shorthand[0], 1)
        return if (close > 0) shorthand.substring(0, close + 1) else null
    }
    return shorthand.lowercase().replace(Regex("url\\([^)]*\\)"), " ").split(' ', '\t')
        .firstOrNull { it == "none" || it in BULLETS || it in NUMBERED }
}

/**
 * The browser default for a list with no `list-style-type`: numbers for
 * `<ol>`; for `<ul>`, a disc, then circles and squares as lists nest
 * ([bulletDepth] = how many `<ul>`s enclose this one).
 */
internal fun defaultListStyle(ordered: Boolean, bulletDepth: Int): String = when {
    ordered -> "decimal"
    bulletDepth == 0 -> "disc"
    bulletDepth == 1 -> "circle"
    else -> "square"
}

/**
 * Item [n]'s marker in list style [type], with its trailing space; null for
 * `none`. Types this reader doesn't know read as `decimal` in an ordered
 * list and a disc otherwise; so do numbers a style can't write (zero or
 * negative letters, roman past 3999), as CSS falls back to decimal.
 */
internal fun listMarker(type: String, n: Int, ordered: Boolean): String? {
    if (type == "none") return null
    if (type.length >= 2 && (type[0] == '"' || type[0] == '\'') && type.last() == type[0]) {
        return type.substring(1, type.length - 1).ifEmpty { null }
    }
    BULLETS[type]?.let { return "$it " }
    val number = when (type) {
        "decimal" -> n.toString()
        "decimal-leading-zero" -> if (n in 0..9) "0$n" else n.toString()
        "lower-alpha", "lower-latin" -> alphabetic(n, 'a', 26)
        "upper-alpha", "upper-latin" -> alphabetic(n, 'A', 26)
        "lower-greek" -> alphabetic(n, 'α', 24)
        "lower-roman" -> roman(n)?.lowercase()
        "upper-roman" -> roman(n)
        else -> if (ordered) n.toString() else return "• "
    } ?: n.toString()
    return "$number. "
}

/** a, b, … z, aa, ab, … (bijective base [size]); null below 1. Greek skips final sigma. */
private fun alphabetic(n: Int, first: Char, size: Int): String? {
    if (n < 1) return null
    val sb = StringBuilder()
    var k = n
    while (k > 0) {
        k--
        var c = first + k % size
        if (first == 'α' && c >= 'ς') c++ // α…ρ, then σ: U+03C2 is the final sigma
        sb.append(c)
        k /= size
    }
    return sb.reverse().toString()
}

private fun roman(n: Int): String? {
    if (n !in 1..3999) return null
    val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
    val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
    val sb = StringBuilder()
    var k = n
    for (i in values.indices) while (k >= values[i]) { sb.append(symbols[i]); k -= values[i] }
    return sb.toString()
}

/**
 * How many items the list whose start tag is `tokens[at]` holds: its own
 * `<li>`s, not those of lists inside it. A `reversed` list with no `start`
 * counts down from this.
 */
internal fun countItems(tokens: List<MarkupToken>, at: Int): Int {
    var depth = 0
    var count = 0
    for (i in at + 1 until tokens.size) {
        when (val t = tokens[i]) {
            is MarkupToken.Start -> when {
                t.selfClosing -> {}
                t.name == "ol" || t.name == "ul" -> depth++
                t.name == "li" && depth == 0 -> count++
            }
            is MarkupToken.End -> if (t.name == "ol" || t.name == "ul") { if (depth == 0) return count; depth-- }
            else -> {}
        }
    }
    return count
}
