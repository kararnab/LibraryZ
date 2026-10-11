package com.libraryz.ui.screens

import com.libraryz.data.api.TokenStore
import com.libraryz.theme.ReadingTheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Keeps [ReaderPrefs] on this device between sessions, as JSON in a
 * one-value [TokenStore]. Enums are stored by name, so renaming or removing
 * one, or a store that can't be read, falls back to the default for that
 * setting rather than losing the rest.
 */
class ReaderPrefsStore(storeFactory: () -> TokenStore) {
    // Opened on first use; if it can't be, settings last for the session.
    private val store: TokenStore? by lazy { runCatching(storeFactory).getOrNull() }

    suspend fun load(): ReaderPrefs? = runCatching { store?.load()?.let(::decode) }.getOrNull()

    suspend fun save(prefs: ReaderPrefs) {
        runCatching { store?.save(encode(prefs)) }
    }

    @Serializable
    private class Saved(
        val textSize: Int? = null,
        val theme: String? = null,
        val spacing: String? = null,
        val width: String? = null,
        val bookFonts: Boolean? = null,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(p: ReaderPrefs): String =
            json.encodeToString(Saved.serializer(), Saved(p.textSize, p.theme.name, p.spacing.name, p.width.name, p.bookFonts))

        fun decode(s: String): ReaderPrefs {
            val saved = json.decodeFromString(Saved.serializer(), s)
            val d = ReaderPrefs()
            return ReaderPrefs(
                textSize = saved.textSize?.coerceIn(ReaderTextSizes) ?: d.textSize,
                theme = ReadingTheme.entries.firstOrNull { it.name == saved.theme } ?: d.theme,
                spacing = LineSpacing.entries.firstOrNull { it.name == saved.spacing } ?: d.spacing,
                width = ColumnWidth.entries.firstOrNull { it.name == saved.width } ?: d.width,
                bookFonts = saved.bookFonts ?: d.bookFonts,
            )
        }
    }
}
