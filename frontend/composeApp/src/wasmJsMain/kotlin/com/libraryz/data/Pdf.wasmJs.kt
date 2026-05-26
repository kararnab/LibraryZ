package com.libraryz.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.skia.Image
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.khronos.webgl.set
import kotlin.coroutines.resume

actual val isPdfPreviewSupported: Boolean = true

/**
 * **Why a handle + callback bridge, not `await()`?**
 *
 * `kotlin.js.Promise<JsAny?>.await()` in Kotlin/Wasm 2.0.21 silently
 * collapses the resolved value to `null` regardless of what JS actually
 * resolved with — verified by logging `id=1` on the JS side and observing
 * `null` on the Kotlin side. Two workarounds compounded here:
 *
 * 1. **Callback bridge instead of `await`** ([awaitHandle] / [awaitBytes]):
 *    JS invokes a Kotlin lambda when the underlying Promise resolves; the
 *    value lives in the Kotlin closure and never crosses the boundary as a
 *    Promise return value.
 * 2. **JS-side document registry**: PDFDocumentProxy stays on the JS side
 *    keyed by an Int id; only the id (a primitive) ever crosses into
 *    Kotlin. Keeps the boundary surface to ints + Int8Array even for
 *    multi-call object lifecycles.
 *
 * Don't replace the callback bridges with `.await()` — pages will silently
 * stay blank.
 */
actual suspend fun openPdfReader(bytes: ByteArray): PagedReader {
    val arr = Int8Array(bytes.size).also { for (i in bytes.indices) it[i] = bytes[i] }
    val handle = awaitHandle { resolve -> pdfJsOpen(arr, resolve) }
    if (handle < 0) error("pdf.js failed to open document (handle=$handle)")
    val pageCount = pdfJsPageCount(handle)
    return WasmPdfReader(handle, pageCount)
}

private suspend inline fun awaitHandle(
    crossinline start: ((Int) -> Unit) -> Unit,
): Int = suspendCancellableCoroutine { cont ->
    start { handle -> cont.resume(handle) }
}

private suspend inline fun awaitBytes(
    crossinline start: ((JsAny?) -> Unit) -> Unit,
): JsAny? = suspendCancellableCoroutine { cont ->
    start { result -> cont.resume(result) }
}

class WasmPdfReader internal constructor(
    private val handle: Int,
    override val pageCount: Int,
) : PagedReader {

    override suspend fun renderPage(pageIndex: Int, widthPx: Int): ImageBitmap {
        val result = awaitBytes { resolve ->
            pdfJsRenderPage(handle, pageIndex, widthPx, resolve)
        } ?: error("pdf.js render returned null")
        val int8 = result.unsafeCast<Int8Array>()
        val bytes = ByteArray(int8.length) { int8[it] }
        return Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }

    override fun close() {
        pdfJsClose(handle)
    }
}

// pdf.js v3 type-checks `data` and only accepts Uint8Array/ArrayBuffer/string.
// We copy our Int8Array view into a fresh Uint8Array on the JS side. The
// resolved PDFDocumentProxy stays in a global registry; only an Int handle
// crosses back into Kotlin.
@JsFun(
    """
    (bytes, resolve) => {
      if (typeof pdfjsLib === 'undefined') {
        console.error('[libraryz] pdfjsLib is undefined — pdf.js script failed to load');
        resolve(-1);
        return;
      }
      if (!globalThis.__libraryzPdfDocs) {
        globalThis.__libraryzPdfDocs = new Map();
        globalThis.__libraryzPdfNextId = 1;
      }
      const data = new Uint8Array(bytes);
      pdfjsLib.getDocument({ data: data }).promise.then(
        doc => {
          const id = globalThis.__libraryzPdfNextId++;
          globalThis.__libraryzPdfDocs.set(id, doc);
          console.log('[libraryz] pdf opened, id=' + id + ', pages=' + doc.numPages);
          resolve(id);
        },
        err => {
          console.error('[libraryz] pdf.js getDocument rejected:', err);
          resolve(-1);
        },
      );
    }
    """
)
private external fun pdfJsOpen(bytes: Int8Array, resolve: (Int) -> Unit)

@JsFun(
    """
    (id) => {
      const doc = globalThis.__libraryzPdfDocs && globalThis.__libraryzPdfDocs.get(id);
      return doc ? doc.numPages : 0;
    }
    """
)
private external fun pdfJsPageCount(id: Int): Int

@JsFun(
    """
    (id, pageIndex, widthPx, resolve) => {
      const doc = globalThis.__libraryzPdfDocs && globalThis.__libraryzPdfDocs.get(id);
      if (!doc) {
        console.error('[libraryz] pdfJsRenderPage: no doc for id=' + id);
        resolve(null);
        return;
      }
      doc.getPage(pageIndex + 1).then(page => {
        const base = page.getViewport({ scale: 1 });
        const scale = Math.max(0.5, Math.min(4, widthPx / base.width));
        const vp = page.getViewport({ scale });
        const canvas = document.createElement('canvas');
        canvas.width = Math.ceil(vp.width);
        canvas.height = Math.ceil(vp.height);
        const ctx = canvas.getContext('2d');
        page.render({ canvasContext: ctx, viewport: vp }).promise.then(() => {
          canvas.toBlob(blob => {
            if (!blob) {
              console.error('[libraryz] canvas.toBlob returned null');
              resolve(null);
              return;
            }
            blob.arrayBuffer().then(buf => {
              console.log('[libraryz] page ' + pageIndex + ' rendered, ' + buf.byteLength + ' png bytes');
              resolve(new Int8Array(buf));
            });
          }, 'image/png');
        });
      }).catch(err => {
        console.error('[libraryz] pdf.js render page ' + pageIndex + ' failed:', err);
        resolve(null);
      });
    }
    """
)
private external fun pdfJsRenderPage(
    id: Int,
    pageIndex: Int,
    widthPx: Int,
    resolve: (JsAny?) -> Unit,
)

@JsFun("(id) => { if (globalThis.__libraryzPdfDocs) globalThis.__libraryzPdfDocs.delete(id); }")
private external fun pdfJsClose(id: Int)
