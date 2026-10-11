package com.libraryz

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.libraryz.data.IMAGE_CHAR
import com.libraryz.ui.screens.textPageBreaks
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Two-page text paginates a measured layout. Table rows are placeholder-only
 * paragraphs with no newline between them, which Skia ends where they
 * start; they must still count as content, so no page runs past its foot
 * (Alice's "List of the Plates" table was clipped mid-row).
 */
class TextPageBreaksTest {
    private val TableRow = ParagraphStyle(
        textAlign = TextAlign.Center,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both, LineHeightStyle.Mode.Fixed),
    )

    @Test
    fun tableRowsStartPagesInsteadOfOverflowingThePreviousOne() {
        val rows = 8
        val text = buildAnnotatedString {
            append("Before the table\n\n")
            // As BookBuilder emits a table: one centred, fixed-height paragraph per row.
            repeat(rows) { withStyle(TableRow) { append(IMAGE_CHAR) } }
            append("\nAfter the table")
        }
        val rowStart = text.text.indexOf(IMAGE_CHAR)
        val placeholders = (0 until rows).map { i ->
            AnnotatedString.Range(Placeholder(200.sp, 60.sp, PlaceholderVerticalAlign.TextTop), rowStart + i, rowStart + i + 1)
        }
        val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
        val layout = measurer.measure(text, TextStyle(fontSize = 16.sp, lineHeight = 24.sp), placeholders = placeholders, constraints = Constraints(maxWidth = 400))
        // The case that broke: lines Skia reports as empty.
        assertTrue((0 until layout.lineCount).any { layout.getLineStart(it) == layout.getLineEnd(it) }, "no empty-range row lines to test")

        val height = 150f
        val starts = textPageBreaks(layout, height)
        starts.forEachIndexed { p, first ->
            val last = (starts.getOrNull(p + 1) ?: layout.lineCount) - 1
            val span = layout.getLineBottom(last) - layout.getLineTop(first)
            assertTrue(span <= height || first == last, "page $p spans ${span}px (lines $first..$last), over $height")
        }
    }
}
