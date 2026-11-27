package com.libraryz

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.libraryz.data.TextReader
import com.libraryz.data.epub
import com.libraryz.data.item
import com.libraryz.data.opf
import com.libraryz.data.parseEpub
import com.libraryz.theme.LibraryZ
import com.libraryz.theme.LibraryZTheme
import com.libraryz.theme.ReadingTheme
import com.libraryz.ui.components.TableLayout
import com.libraryz.ui.components.layoutTable
import kotlin.test.Test
import kotlin.test.assertTrue

/** Table columns measured with the reader's real fonts. */
class TableLayoutTest {

    @Test
    fun aColumnAtItsWidestTextStillFitsItInTheRow() {
        // The header's bold "Year" is its column's widest text. With the columns summing to the
        // row's whole width, the row's placeholder (back through sp, a fraction narrower) squeezed the
        // last cell by a pixel: "Year" wrapped, and the one-line row showed "Yea".
        val book = parseEpub(
            epub(
                opf(item("a", "a.xhtml"), """<itemref idref="a"/>"""),
                "book/a.xhtml" to """<body><table><tr><th>Topic</th><th>Notes</th><th>Year</th></tr>
                    <tr><td>Antidisestablishmentarianism</td><td>A long word in a narrow column of a table that has to share its width.</td><td>1838</td></tr>
                    </table></body>""",
            ),
        )
        val reader = TextReader(book.text, book.chapters, book.images, book.tables, book.boxes, styled = true)
        var layout: TableLayout? = null
        var year = 0f
        val scene = ImageComposeScene(800, 600, Density(1f)) {
            LibraryZTheme {
                val measurer = rememberTextMeasurer()
                val style = TextStyle(fontFamily = LibraryZ.tokens.serif, fontSize = 18.sp)
                year = measurer.measure(reader.tables.single().rows[0][2].text, style).multiParagraph.intrinsics.maxIntrinsicWidth
                layout = layoutTable(reader.tables.single(), reader, Dp(551f), Dp(500f), style, ReadingTheme.Light, LocalDensity.current, measurer)
            }
        }
        scene.render(0)
        scene.close()
        val columns = layout!!.columns.map { it.value }
        assertTrue(columns.sum() <= 550f, "columns $columns fill the whole 551 row")
        // Its 8 + 8 padding around at least the widest text, whole pixels.
        assertTrue(columns[2] >= year + 16f, "Year column ${columns[2]} for text $year")
    }
}
