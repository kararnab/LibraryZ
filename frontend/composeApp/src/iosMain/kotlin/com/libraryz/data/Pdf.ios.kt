package com.libraryz.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import platform.CoreGraphics.CGSizeMake
import platform.PDFKit.PDFDocument
import platform.PDFKit.PDFDisplayBox
import platform.UIKit.UIImagePNGRepresentation

// Drafted, not yet compiled — iosMain only links on macOS. Verify on a Mac.
//
// Mirrors the Android (PdfRenderer) and Desktop (PDFBox) actuals: render a page
// to a platform image, PNG-encode it, then go bytes -> Skia Image -> Compose
// ImageBitmap (the same final hop the Desktop/Wasm backends use).
actual val isPdfPreviewSupported: Boolean = true

@OptIn(ExperimentalForeignApi::class)
actual suspend fun openPdfReader(bytes: ByteArray): PagedReader =
    withContext(Dispatchers.Default) {
        val doc = PDFDocument(data = bytes.toNSData())
            ?: error("could not parse PDF")
        IosPdfReader(doc)
    }

@OptIn(ExperimentalForeignApi::class)
private class IosPdfReader(private val doc: PDFDocument) : PagedReader {

    override val pageCount: Int get() = doc.pageCount.toInt()

    override suspend fun renderPage(pageIndex: Int, widthPx: Int): ImageBitmap =
        withContext(Dispatchers.Default) {
            val page = doc.pageAtIndex(pageIndex.toULong())
                ?: error("no page at index $pageIndex")

            // Preserve the page aspect ratio: scale the media box to widthPx.
            val box = PDFDisplayBox.kPDFDisplayBoxMediaBox
            val (w, h) = page.boundsForBox(box).useContents { size.width to size.height }
            val scale = if (w > 0) widthPx / w else 1.0
            val targetSize = CGSizeMake(widthPx.toDouble(), h * scale)

            // thumbnailOfSize renders the page into a UIImage off the main thread.
            val image = page.thumbnailOfSize(targetSize, forBox = box)
            val png = UIImagePNGRepresentation(image)
                ?: error("failed to PNG-encode rendered page")
            Image.makeFromEncoded(png.toByteArray()).toComposeImageBitmap()
        }

    // PDFDocument has no explicit dispose; ARC reclaims it once unreferenced.
    override fun close() {}
}
