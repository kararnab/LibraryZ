package com.libraryz.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.caverock.androidsvg.SVG

internal actual fun rasterizeSvg(bytes: ByteArray, intrinsicWidth: Int, intrinsicHeight: Int, width: Int, height: Int): ImageBitmap? =
    runCatching {
        val svg = SVG.getFromInputStream(bytes.inputStream())
        // Without a viewBox, AndroidSVG can't scale the drawing to a new size.
        if (svg.documentViewBox == null) svg.setDocumentViewBox(0f, 0f, intrinsicWidth.toFloat(), intrinsicHeight.toFloat())
        svg.setDocumentWidth(width.toFloat())
        svg.setDocumentHeight(height.toFloat())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(Canvas(bitmap))
        bitmap.asImageBitmap()
    }.getOrNull()
