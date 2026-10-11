package com.libraryz.data

/**
 * One piece of XML / XHTML, from [markupTokens]. Element and attribute names
 * are lowercased; elements lose their namespace prefix (`xhtml:p` → `p`),
 * attributes keep it (`epub:type`).
 */
sealed interface MarkupToken {
    data class Start(val name: String, val attrs: Map<String, String>, val selfClosing: Boolean) : MarkupToken
    data class End(val name: String) : MarkupToken
    /** Character data, entities already decoded. */
    data class Text(val text: String) : MarkupToken
}

/**
 * Splits [src] into tags and text. Lenient on purpose: EPUBs in the wild
 * carry HTML that isn't well-formed XML (stray `&`, unquoted attributes,
 * unclosed tags), so this never throws; whoever walks the tokens decides
 * what nesting means. Comments, processing instructions and doctypes are
 * dropped; CDATA becomes text.
 */
fun markupTokens(src: String): List<MarkupToken> {
    val out = ArrayList<MarkupToken>()
    var i = 0
    val n = src.length
    while (i < n) {
        val lt = src.indexOf('<', i)
        if (lt != i) {
            val stop = if (lt < 0) n else lt
            out += MarkupToken.Text(decodeEntities(src.substring(i, stop)))
            i = stop
            continue
        }
        when {
            src.startsWith("<!--", i) -> {
                val close = src.indexOf("-->", i + 4)
                i = if (close < 0) n else close + 3
            }
            src.startsWith("<![CDATA[", i) -> {
                val close = src.indexOf("]]>", i + 9)
                val stop = if (close < 0) n else close
                out += MarkupToken.Text(src.substring(i + 9, stop))
                i = if (close < 0) n else close + 3
            }
            i + 1 < n && (src[i + 1] == '!' || src[i + 1] == '?') -> {
                val close = src.indexOf('>', i)
                i = if (close < 0) n else close + 1
            }
            i + 1 < n && src[i + 1] == '/' -> {
                val close = src.indexOf('>', i)
                val stop = if (close < 0) n else close
                out += MarkupToken.End(localName(src.substring(i + 2, stop).trim()))
                i = if (close < 0) n else close + 1
            }
            i + 1 < n && src[i + 1].isLetter() -> i = startTag(src, i, out)
            else -> {
                // A bare '<' in text.
                out += MarkupToken.Text("<")
                i++
            }
        }
    }
    return out
}

/** Parses the start tag at [from] into [out]; returns the index after it. */
private fun startTag(src: String, from: Int, out: MutableList<MarkupToken>): Int {
    val n = src.length
    var i = from + 1
    val nameStart = i
    while (i < n && !src[i].isWhitespace() && src[i] != '>' && src[i] != '/') i++
    val name = localName(src.substring(nameStart, i))
    val attrs = LinkedHashMap<String, String>()
    var selfClosing = false
    while (i < n) {
        val c = src[i]
        when {
            c == '>' -> { i++; break }
            c == '/' -> { selfClosing = true; i++ }
            c.isWhitespace() -> i++
            else -> {
                val keyStart = i
                while (i < n && !src[i].isWhitespace() && src[i] != '=' && src[i] != '>' && src[i] != '/') i++
                val key = src.substring(keyStart, i).lowercase()
                while (i < n && src[i].isWhitespace()) i++
                var value = ""
                if (i < n && src[i] == '=') {
                    i++
                    while (i < n && src[i].isWhitespace()) i++
                    if (i < n && (src[i] == '"' || src[i] == '\'')) {
                        val quote = src[i]
                        val close = src.indexOf(quote, i + 1).let { if (it < 0) n else it }
                        value = src.substring(i + 1, close)
                        i = minOf(n, close + 1)
                    } else {
                        val valueStart = i
                        while (i < n && !src[i].isWhitespace() && src[i] != '>') i++
                        value = src.substring(valueStart, i)
                    }
                }
                if (key.isNotEmpty()) attrs[key] = decodeEntities(value)
                selfClosing = false
            }
        }
    }
    out += MarkupToken.Start(name, attrs, selfClosing)
    return i
}

private fun localName(qualified: String): String =
    qualified.substringAfter(':').lowercase()

/** Decodes `&amp;`, `&#8217;`, `&#x2014;` and the common HTML names; anything else is left as written. */
fun decodeEntities(s: String): String {
    var amp = s.indexOf('&')
    if (amp < 0) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (amp >= 0) {
        sb.appendRange(s, i, amp)
        val semi = s.indexOf(';', amp)
        val decoded = if (semi in amp + 2..amp + 12) entity(s.substring(amp + 1, semi)) else null
        if (decoded != null) {
            sb.append(decoded)
            i = semi + 1
        } else {
            sb.append('&')
            i = amp + 1
        }
        amp = s.indexOf('&', i)
    }
    sb.appendRange(s, i, s.length)
    return sb.toString()
}

private fun entity(name: String): String? {
    if (name.startsWith("#")) {
        val code = if (name.startsWith("#x") || name.startsWith("#X")) name.drop(2).toIntOrNull(16) else name.drop(1).toIntOrNull()
        if (code == null || code <= 0 || code > 0x10FFFF || code in 0xD800..0xDFFF) return null
        return if (code < 0x10000) {
            code.toChar().toString()
        } else {
            val v = code - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
    }
    return NAMED_ENTITIES[name]
}

private val NAMED_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "nbsp" to " ", "shy" to "­", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
    "ndash" to "–", "mdash" to "—", "hellip" to "…", "bull" to "•", "middot" to "·",
    "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "sbquo" to "‚", "bdquo" to "„",
    "laquo" to "«", "raquo" to "»", "lsaquo" to "‹", "rsaquo" to "›",
    "copy" to "©", "reg" to "®", "trade" to "™", "sect" to "§", "para" to "¶", "dagger" to "†", "Dagger" to "‡",
    "deg" to "°", "times" to "×", "divide" to "÷", "frac12" to "½", "frac14" to "¼", "frac34" to "¾",
    "pound" to "£", "euro" to "€", "yen" to "¥", "cent" to "¢",
    "iexcl" to "¡", "iquest" to "¿", "ordf" to "ª", "ordm" to "º", "prime" to "′", "Prime" to "″",
    "aacute" to "á", "agrave" to "à", "acirc" to "â", "auml" to "ä", "atilde" to "ã", "aring" to "å", "aelig" to "æ",
    "ccedil" to "ç", "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "euml" to "ë",
    "iacute" to "í", "igrave" to "ì", "icirc" to "î", "iuml" to "ï", "ntilde" to "ñ",
    "oacute" to "ó", "ograve" to "ò", "ocirc" to "ô", "ouml" to "ö", "otilde" to "õ", "oslash" to "ø", "oelig" to "œ",
    "uacute" to "ú", "ugrave" to "ù", "ucirc" to "û", "uuml" to "ü", "yacute" to "ý", "yuml" to "ÿ", "szlig" to "ß",
    "Aacute" to "Á", "Agrave" to "À", "Acirc" to "Â", "Auml" to "Ä", "Aring" to "Å", "AElig" to "Æ", "Ccedil" to "Ç",
    "Eacute" to "É", "Egrave" to "È", "Ecirc" to "Ê", "Euml" to "Ë", "Iacute" to "Í", "Icirc" to "Î", "Ntilde" to "Ñ",
    "Oacute" to "Ó", "Ocirc" to "Ô", "Ouml" to "Ö", "Oslash" to "Ø", "OElig" to "Œ", "Uacute" to "Ú", "Uuml" to "Ü",
)
