package com.libraryz.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.jetbrains.skia.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

actual val isPdfPreviewSupported: Boolean = true

actual suspend fun openPdfReader(bytes: ByteArray): PagedReader =
    withContext(Dispatchers.IO) { DesktopPdfReader(bytes) }

class DesktopPdfReader(bytes: ByteArray) : PagedReader {
    private val doc: PDDocument = Loader.loadPDF(bytes)
    private val renderer: PDFRenderer = PDFRenderer(doc)

    override val pageCount: Int get() = doc.numberOfPages

    override suspend fun renderPage(pageIndex: Int, widthPx: Int): ImageBitmap =
        withContext(Dispatchers.IO) {
            val pageSize = doc.getPage(pageIndex).mediaBox
            val dpi = (widthPx / pageSize.width * 72f).coerceIn(72f, 240f)
            val img: BufferedImage = renderer.renderImageWithDPI(pageIndex, dpi)
            // BufferedImage -> PNG bytes -> Skia Image -> Compose ImageBitmap.
            // Slightly indirect but avoids hunting for the skiko extension that
            // converts directly, and PNG round-trip is cheap relative to render.
            val baos = ByteArrayOutputStream()
            ImageIO.write(img, "png", baos)
            Image.makeFromEncoded(baos.toByteArray()).toComposeImageBitmap()
        }

    override fun close() {
        runCatching { doc.close() }
    }
}
