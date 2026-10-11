package com.libraryz.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Data
import org.jetbrains.skia.svg.SVGDOM

internal actual fun rasterizeSvg(bytes: ByteArray, intrinsicWidth: Int, intrinsicHeight: Int, width: Int, height: Int): ImageBitmap? =
    runCatching {
        val dom = SVGDOM(Data.makeFromBytes(bytes))
        // Lay the drawing out at its own size, then scale the canvas to the target.
        dom.setContainerSize(intrinsicWidth.toFloat(), intrinsicHeight.toFloat())
        val bitmap = Bitmap()
        bitmap.allocN32Pixels(width, height)
        val canvas = Canvas(bitmap)
        canvas.scale(width.toFloat() / intrinsicWidth, height.toFloat() / intrinsicHeight)
        dom.render(canvas)
        bitmap.setImmutable()
        bitmap.asComposeImageBitmap()
    }.getOrNull()
