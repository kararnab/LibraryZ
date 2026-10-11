package com.libraryz.ui.components

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Draws the SVG [bytes] ([intrinsicWidth] × [intrinsicHeight] CSS pixels,
 * from [com.libraryz.data.svgSize]) into a [width] × [height] bitmap, or null
 * if it can't be read. Skia's SVG renderer on desktop, web and iOS;
 * AndroidSVG on Android, which has no SVG support in Compose. Neither runs
 * scripts or fetches external files.
 */
internal expect fun rasterizeSvg(bytes: ByteArray, intrinsicWidth: Int, intrinsicHeight: Int, width: Int, height: Int): ImageBitmap?
