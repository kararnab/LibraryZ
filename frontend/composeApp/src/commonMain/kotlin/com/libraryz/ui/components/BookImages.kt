package com.libraryz.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.min
import com.libraryz.data.BookImage
import com.libraryz.data.IMAGE_TAG
import com.libraryz.data.TextReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * A [TextReader]'s text ready to lay out with its pictures: pass [text] and
 * [inlineContent] to `Text`, or [text] and [placeholders] to a `TextMeasurer`,
 * with [lineHeightStyle] in the style either way.
 */
class SizedText(
    val text: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>,
    val placeholders: List<AnnotatedString.Range<Placeholder>>,
    /**
     * Lets a line grow past the style's line height to fit its picture.
     * Android's default ([LineHeightStyle.Mode.Fixed]) would crop it to one
     * line of text; Skia grows the line either way. Null without pictures, so
     * plain text lays out as it always has.
     */
    val lineHeightStyle: LineHeightStyle?,
)

/**
 * [reader]'s text with each picture given a size. A block picture's
 * placeholder spans the column, [maxWidth], so the picture is drawn centered
 * in it, as wide as it is (one image pixel = 1 dp) up to that, then shrunk
 * to fit [maxHeight]. An inline one is an em high.
 */
@Composable
fun rememberSizedText(reader: TextReader, maxWidth: Dp, maxHeight: Dp): SizedText {
    val density = LocalDensity.current
    return remember(reader, maxWidth, maxHeight, density) {
        sizeImages(reader, maxWidth, maxHeight, density)
    }
}

internal fun sizeImages(reader: TextReader, maxWidth: Dp, maxHeight: Dp, density: Density): SizedText {
    val text = reader.text
    if (reader.images.isEmpty()) return SizedText(text, emptyMap(), emptyList(), null)
    val placeholders = reader.images.mapValues { (_, image) ->
        if (!image.block) {
            Placeholder(
                width = (image.width.toFloat() / image.height).em,
                height = 1.em,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            )
        } else with(density) {
            val w = min(maxWidth, image.width.dp)
            val h = min(maxHeight, w * image.height.toFloat() / image.width.toFloat())
            Placeholder(maxWidth.toSp(), h.toSp(), PlaceholderVerticalAlign.Center)
        }
    }
    return SizedText(
        text = text,
        inlineContent = reader.images.mapValues { (key, image) ->
            InlineTextContent(placeholders.getValue(key)) { BookImageView(image) }
        },
        placeholders = text.getStringAnnotations(IMAGE_TAG, 0, text.length).mapNotNull { r ->
            placeholders[r.item]?.let { AnnotatedString.Range(it, r.start, r.end) }
        },
        lineHeightStyle = LineHeightStyle.Default.copy(mode = LineHeightStyle.Mode.Minimum),
    )
}

/**
 * Decodes [image] while any of it is on screen and lets it go once it
 * scrolls away, so a long illustrated book holds only a few bitmaps. A file
 * the platform can't decode leaves its space empty.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun BookImageView(image: BookImage) {
    var visible by remember(image) { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(null, image, visible) {
        value = if (visible) {
            withContext(Dispatchers.Default) { runCatching { image.bytes().decodeToImageBitmap() }.getOrNull() }
        } else {
            null
        }
    }
    Box(
        Modifier.fillMaxSize().onGloballyPositioned {
            val bounds = it.boundsInWindow()
            visible = bounds.width > 0f && bounds.height > 0f
        },
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = image.alt.ifEmpty { null },
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
