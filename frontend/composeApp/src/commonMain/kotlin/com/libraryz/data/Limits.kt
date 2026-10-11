package com.libraryz.data

/**
 * The server's bounds on what a reader types (it answers 400 past them).
 * A library change the server refuses is dropped from the queue, so the
 * fields that feed it stop at these rather than lose an edit made offline.
 * The server counts code points and [capped] counts UTF-16 units, so a
 * capped string always fits.
 */
object Limits {
    /** library.MaxNotesRunes */
    const val NOTES = 20_000
    /** library.MaxShelfRunes */
    const val SHELF = 100
    /** auth.MaxNameRunes */
    const val NAME = 200
    /** catalog.MaxQueryRunes */
    const val QUERY = 200
}

/** At most [max] UTF-16 units, without splitting a surrogate pair. */
fun String.capped(max: Int): String {
    if (length <= max) return this
    val end = if (this[max - 1].isHighSurrogate()) max - 1 else max
    return substring(0, end)
}
