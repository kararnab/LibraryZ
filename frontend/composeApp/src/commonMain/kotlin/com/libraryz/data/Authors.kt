package com.libraryz.data

/*
 * How a work's authors read in each place they appear. The stored value is
 * one string, names separated by ";" (commas may sit inside a name, as in
 * "Brooks, Frederick P."). Order is kept: the first author leads everywhere.
 */

/** The names in [authors], trimmed, empties dropped. */
fun splitAuthors(authors: String?): List<String> =
    authors.orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }

/** The stored form of [names]: "A; B; C". */
fun joinAuthors(names: List<String>): String = names.joinToString("; ")

private val suffix = Regex("^(jr|sr)\\.?$|^(ii|iii|iv)$", RegexOption.IGNORE_CASE)

/**
 * The surname of [name]: the part before the comma for "Surname, Given",
 * else the last word, skipping a suffix ("Frederick P. Brooks Jr." → Brooks).
 */
fun surname(name: String): String {
    var n = name.trim()
    // A trailing suffix, with or without a comma: "Brooks, Jr." / "Brooks Jr."
    val tail = n.substringAfterLast(',', "").trim()
    if (tail.isNotEmpty() && suffix.matches(tail)) n = n.substringBeforeLast(',').trim()
    if (',' in n) return n.substringBefore(',').trim().ifEmpty { n }
    val words = n.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return n
    var i = words.lastIndex
    if (i > 0 && suffix.matches(words[i])) i--
    return words[i]
}

/** "A", "A and B", "A, B and C" ([amp]: "A, B & C"). */
private fun joinAnd(xs: List<String>, amp: Boolean): String = when (xs.size) {
    0 -> ""
    1 -> xs[0]
    else -> xs.dropLast(1).joinToString(", ") + (if (amp) " & " else " and ") + xs.last()
}

/**
 * List rows and the reader's top bar: one author in full, up to three
 * surnames joined with "&", four or more as "Aho et al.".
 */
fun authorsShort(authors: String?): String {
    val a = splitAuthors(authors)
    return when {
        a.isEmpty() -> ""
        a.size == 1 -> a[0]
        a.size <= 3 -> joinAnd(a.map(::surname), amp = true)
        else -> surname(a[0]) + " et al."
    }
}

/** Book detail: every name, "and" before the last; past six, the first five and "and N more". */
fun authorsFull(authors: String?): String {
    val a = splitAuthors(authors)
    return if (a.size <= 6) joinAnd(a, amp = false) else a.take(5).joinToString(", ") + " and ${a.size - 5} more"
}

/** Medium covers: the lead surname, then "+N" for the rest. */
fun coverAuthorsM(authors: String?): String {
    val a = splitAuthors(authors)
    if (a.isEmpty()) return ""
    return if (a.size == 1) surname(a[0]) else "${surname(a[0])} +${a.size - 1}"
}

/** Large covers: a single name in full if it fits (14 characters), else as [coverAuthorsM]. */
fun coverAuthorsL(authors: String?): String {
    val a = splitAuthors(authors)
    return if (a.size == 1 && a[0].length <= 14) a[0] else coverAuthorsM(authors)
}

/** Extra-large covers (two lines): one name in full, up to four surnames with "&", else "et al.". */
fun coverAuthorsXL(authors: String?): String {
    val a = splitAuthors(authors)
    return when {
        a.isEmpty() -> ""
        a.size == 1 -> a[0]
        a.size <= 4 -> joinAnd(a.map(::surname), amp = true)
        else -> surname(a[0]) + " et al."
    }
}

/**
 * The author input field's state: the names entered as chips, the text still
 * being typed, and the chip a duplicate was pointed at (null when none).
 */
data class AuthorEntry(val names: List<String>, val draft: String, val duplicate: Int? = null) {
    /** The stored value, the unfinished name included so it isn't lost on submit. */
    val value: String get() = joinAuthors(names + draft.trim().takeIf { it.isNotEmpty() }.let(::listOfNotNull))
}

private fun indexOfName(names: List<String>, name: String) = names.indexOfFirst { it.equals(name, ignoreCase = true) }

/** Enter (or ";"): the typed name becomes a chip, unless it's already listed. */
fun commitAuthor(entry: AuthorEntry): AuthorEntry {
    val name = entry.draft.trim()
    if (name.isEmpty()) return entry.copy(draft = "", duplicate = null)
    val dup = indexOfName(entry.names, name)
    return if (dup >= 0) entry.copy(duplicate = dup) else AuthorEntry(entry.names + name, "")
}

/**
 * The field's text changed to [text]. A ";" ends a name; a paste of
 * "A; B; C" becomes three chips (names already listed are skipped).
 */
fun typeAuthors(entry: AuthorEntry, text: String): AuthorEntry {
    if (';' !in text) return entry.copy(draft = text, duplicate = null)
    val parts = text.split(';')
    val pasted = text.length - entry.draft.length > 1
    if (!pasted && parts.size == 2) return commitAuthor(entry.copy(draft = parts[0])).let {
        if (it.duplicate == null) it.copy(draft = parts[1]) else it
    }
    var names = entry.names
    (if (pasted) parts else parts.dropLast(1)).map { it.trim() }.filter { it.isNotEmpty() }.forEach { name ->
        if (indexOfName(names, name) < 0) names = names + name
    }
    return AuthorEntry(names, if (pasted) "" else parts.last())
}
