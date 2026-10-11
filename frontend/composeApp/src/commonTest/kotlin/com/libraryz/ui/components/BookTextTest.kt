package com.libraryz.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.libraryz.theme.ReadingTheme
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The book's colours, made readable on each reading theme. */
class BookTextTest {

    private fun contrast(a: Color, b: Color) =
        (max(a.luminance(), b.luminance()) + 0.05f) / (min(a.luminance(), b.luminance()) + 0.05f)

    @Test
    fun textColoursReadOnEveryTheme() {
        for (theme in ReadingTheme.entries) {
            for (color in listOf(Color(0xFF000080), Color(0xFFFFEB3B), Color(0xFFC62828), Color(0xFF2E7D32))) {
                val ink = readableInk(color, theme.background)
                assertTrue(contrast(ink, theme.background) >= 4f, "$color on $theme: ${contrast(ink, theme.background)}")
            }
            // Black and greys are body-text colours: the theme's ink takes over.
            assertEquals(Color.Unspecified, readableInk(Color(0xFF333333), theme.background))
        }
    }

    @Test
    fun highlightsKeepTheInkReadable() {
        for (theme in ReadingTheme.entries) {
            for (color in listOf(Color(0xFFFFF176), Color(0xFFC8E6C9), Color(0xFF0D47A1), Color(0x80FF0000))) {
                val fill = highlight(color, theme)!!
                assertTrue(contrast(theme.ink, fill) >= 4.5f, "$color on $theme: ${contrast(theme.ink, fill)}")
            }
            assertNull(highlight(Color.White, theme))
            assertNull(highlight(Color(0xFFEEEEEE), theme))
        }
    }

    @Test
    fun themingKeepsTheTextAndItsAnnotations() {
        val text = buildAnnotatedString {
            append("a ")
            pushStringAnnotation("tag", "x")
            withStyle(SpanStyle(color = Color(0xFF000080))) { append("navy") }
            pop()
        }
        val dark = themed(text, ReadingTheme.Dark)
        assertEquals(text.text, dark.text)
        assertEquals("x", dark.getStringAnnotations("tag", 0, dark.length).single().item)
        assertTrue(contrast(dark.spanStyles.single().item.color, ReadingTheme.Dark.background) >= 4f)
    }
}
