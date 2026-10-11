package com.libraryz.ui.screens

import com.libraryz.data.api.TokenStore
import com.libraryz.theme.ReadingTheme
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReaderPrefsStoreTest {

    private class Memory(var value: String? = null) : TokenStore {
        override suspend fun load() = value
        override suspend fun save(token: String) { value = token }
        override suspend fun clear() { value = null }
    }

    @Test
    fun keepsSettingsBetweenSessions() = runTest {
        val disk = Memory()
        val prefs = ReaderPrefs(textSize = 22, theme = ReadingTheme.Dark, spacing = LineSpacing.Airy, width = ColumnWidth.Wide, bookFonts = false)
        ReaderPrefsStore { disk }.save(prefs)
        assertEquals(prefs, ReaderPrefsStore { disk }.load())
    }

    @Test
    fun fallsBackPerSettingAndNeverThrows() = runTest {
        // An unknown theme and an out-of-range size reset just those settings.
        val odd = Memory("""{"textSize":99,"theme":"Neon","spacing":"Compact","extra":1}""")
        assertEquals(ReaderPrefs(textSize = ReaderTextSizes.last, spacing = LineSpacing.Compact), ReaderPrefsStore { odd }.load())
        assertNull(ReaderPrefsStore { Memory("not json") }.load())
        assertNull(ReaderPrefsStore { Memory() }.load())
        // A store that can't be opened: settings last for the session.
        val broken = ReaderPrefsStore { error("no disk") }
        broken.save(ReaderPrefs())
        assertNull(broken.load())
    }
}
