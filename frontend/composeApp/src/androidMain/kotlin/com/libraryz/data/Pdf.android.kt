package com.libraryz.data

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.libraryz.data.api.AndroidContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual val isPdfPreviewSupported: Boolean = true

actual suspend fun openPdfReader(bytes: ByteArray): PagedReader =
    withContext(Dispatchers.IO) { AndroidPdfReader(bytes) }

class AndroidPdfReader(bytes: ByteArray) : PagedReader {
    private val file: File
    private val pfd: ParcelFileDescriptor
    private val renderer: PdfRenderer

    init {
        val ctx = AndroidContextHolder.appContext
        val tmp = File(
            ctx.cacheDir,
            "preview-${System.currentTimeMillis()}-${bytes.size}.pdf",
        )
        tmp.writeBytes(bytes)
        file = tmp
        pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(pfd)
    }

    override val pageCount: Int = renderer.pageCount

    override suspend fun renderPage(pageIndex: Int, widthPx: Int): ImageBitmap =
        withContext(Dispatchers.IO) {
            val page = renderer.openPage(pageIndex)
            try {
                val ratio = page.height.toFloat() / page.width.toFloat()
                val w = widthPx.coerceAtLeast(200)
                val h = (w * ratio).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp.asImageBitmap()
            } finally {
                page.close()
            }
        }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
        runCatching { file.delete() }
    }
}
