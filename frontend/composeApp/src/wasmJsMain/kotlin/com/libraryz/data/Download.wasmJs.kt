package com.libraryz.data

import org.khronos.webgl.Int8Array
import org.khronos.webgl.set

actual val isDownloadSupported: Boolean = true

/**
 * Triggers a browser download. ByteArray itself can't cross the @JsFun
 * boundary in Kotlin/Wasm, so we copy bytes into an `Int8Array` (which is
 * an external JS type) and hand that to JS, where it's wrapped in a Blob
 * and downloaded via a synthesized `<a download>` click.
 *
 * For a typical book (≤50 MB) the copy is fine; if we ever need to support
 * multi-hundred-MB downloads, switch the source to a streaming fetch
 * response and use the same blob-URL trick on a Response.body stream.
 */
actual suspend fun saveDownload(name: String, bytes: ByteArray): String {
    val arr = Int8Array(bytes.size)
    for (i in bytes.indices) arr[i] = bytes[i]
    triggerBrowserDownload(name, arr)
    return "Saved to your browser's downloads."
}

@JsFun(
    """
    (name, bytes) => {
        const blob = new Blob([bytes], { type: 'application/octet-stream' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = name;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    }
    """
)
private external fun triggerBrowserDownload(name: String, bytes: Int8Array)
