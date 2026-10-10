package com.libraryz.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.libraryz.data.coverAuthorsL
import com.libraryz.data.coverAuthorsM
import com.libraryz.data.coverAuthorsXL
import com.libraryz.theme.LibraryZ
import kotlin.math.abs

/** Cloth colors (background, ink), indexed by [coverIndex]. */
private val CoverCloth = listOf(
    Color(0xFF2F5D50) to Color(0xFFF1EAD4), // forest
    Color(0xFF7A3B34) to Color(0xFFF6E6D6), // oxblood
    Color(0xFF2D4263) to Color(0xFFECE5D2), // navy
    Color(0xFFC1923C) to Color(0xFF2A1E08), // ochre
    Color(0xFF5B3D5C) to Color(0xFFF2E4EB), // plum
    Color(0xFF4D5A5E) to Color(0xFFEDF0E9), // slate
    Color(0xFF9BAE94) to Color(0xFF1C2A1B), // sage
    Color(0xFFB0603F) to Color(0xFFFFF0E3), // terracotta
)

/**
 * Which cloth a title gets. Derived from the title alone so a book looks
 * the same on every screen and device: a 31-multiplier string hash (with
 * Int overflow, matching the design's reference implementation).
 */
internal fun coverIndex(title: String): Int {
    val h = title.lowercase().fold(0) { a, c -> a * 31 + c.code }
    return abs(h % CoverCloth.size)
}

/** The letter shown on small covers: the first after a leading article. */
internal fun monogram(title: String): String =
    title.trim().replace(Regex("^(The|A|An) ", RegexOption.IGNORE_CASE), "")
        .firstOrNull()?.uppercase() ?: "?"

/** True when a line of [layout] ends inside a word of [text]. */
private fun breaksMidWord(layout: TextLayoutResult, text: String): Boolean {
    for (line in 0 until layout.lineCount - 1) {
        val end = layout.getLineEnd(line)
        if (end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()) return true
    }
    return false
}

enum class CoverSize(
    val width: Dp,
    val height: Dp,
    val padStart: Dp,
    val padTop: Dp,
    val padEnd: Dp,
    val padBottom: Dp,
    val title: TextUnit,
    val titleLine: TextUnit,
    val author: TextUnit,
    val authorLine: TextUnit,
    val rule: Dp,
    val ruleGap: Dp,
) {
    /** List rows: monogram only. */
    S(40.dp, 60.dp, 0.dp, 0.dp, 0.dp, 0.dp, 0.sp, 0.sp, 0.sp, 0.sp, 0.dp, 0.dp),
    M(72.dp, 108.dp, 11.dp, 12.dp, 8.dp, 11.dp, 11.sp, 13.sp, 6.5.sp, 8.sp, 14.dp, 6.dp),
    L(120.dp, 180.dp, 18.dp, 20.dp, 14.dp, 18.dp, 16.sp, 19.sp, 8.5.sp, 11.sp, 24.dp, 10.dp),
    XL(160.dp, 240.dp, 24.dp, 26.dp, 18.dp, 24.dp, 21.sp, 25.sp, 10.sp, 13.sp, 24.dp, 10.dp),
}

/**
 * A typographic stand-in cover, since works have no cover images: cloth
 * color from the title, a darker spine edge, an inset hairline frame,
 * title in Literata, a short rule, and the author in tracked caps. At
 * [CoverSize.S] it drops to a monogram.
 */
@Composable
fun BookCover(
    title: String,
    authors: String?,
    size: CoverSize,
    modifier: Modifier = Modifier,
) {
    val (cloth, ink) = CoverCloth[coverIndex(title)]
    // The design mutes covers slightly on dark surfaces.
    val bg = if (LibraryZ.tokens.dark) lerp(cloth, Color.Black, 0.08f) else cloth
    val shape = RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp, topEnd = 5.dp, bottomEnd = 5.dp)
    val serif = LibraryZ.tokens.serif
    Box(
        modifier = modifier
            .size(size.width, size.height)
            .shadow(2.dp, shape)
            .clip(shape)
            .background(bg)
            .drawWithContent {
                drawContent()
                // Spine: a dark edge with a faint highlight beside it.
                drawRect(Color.Black.copy(alpha = 0.16f), size = Size(3.dp.toPx(), this.size.height))
                drawRect(
                    Color.White.copy(alpha = 0.08f),
                    topLeft = Offset(3.dp.toPx(), 0f),
                    size = Size(1.dp.toPx(), this.size.height),
                )
            },
    ) {
        // Hairline frame, inset 7% / 8% / 7% / 11% (start leaves room for the spine).
        Box(
            Modifier
                .padding(
                    start = size.width * 0.11f,
                    top = size.height * 0.07f,
                    end = size.width * 0.08f,
                    bottom = size.height * 0.07f,
                )
                .fillMaxSize()
                .border(1.dp, ink.copy(alpha = 0.32f), RoundedCornerShape(1.dp)),
        )
        if (size == CoverSize.S) {
            Text(
                text = monogram(title),
                color = ink,
                fontFamily = serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                modifier = Modifier.align(Alignment.Center).padding(start = 3.dp),
            )
        } else {
            // Shrink the title while any line breaks mid-word, so
            // "Middlemarch" never splits on a narrow cover. Driven by the
            // real layout, so it also corrects itself once Literata loads.
            var scale by remember(title, size) { mutableStateOf(1f) }
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(start = size.padStart, top = size.padTop, end = size.padEnd, bottom = size.padBottom),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = title,
                        color = ink,
                        fontFamily = serif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = size.title * scale,
                        lineHeight = size.titleLine * scale,
                        onTextLayout = { layout ->
                            if (scale > 0.6f && breaksMidWord(layout, title)) scale -= 0.05f
                        },
                        letterSpacing = (-0.005).em,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(
                        Modifier
                            .padding(top = size.ruleGap)
                            .width(size.rule)
                            .height(1.dp)
                            .background(ink.copy(alpha = 0.5f)),
                    )
                }
                val credit = when (size) {
                    CoverSize.XL -> coverAuthorsXL(authors)
                    CoverSize.L -> coverAuthorsL(authors)
                    else -> coverAuthorsM(authors)
                }
                if (credit.isNotEmpty()) {
                    Text(
                        text = credit.uppercase(),
                        color = ink.copy(alpha = 0.85f),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = size.author,
                        lineHeight = size.authorLine,
                        letterSpacing = 0.12.em,
                        maxLines = if (size == CoverSize.XL) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
