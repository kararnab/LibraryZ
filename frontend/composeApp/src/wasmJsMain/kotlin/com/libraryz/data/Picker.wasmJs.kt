package com.libraryz.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.browser.document
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File
import org.w3c.files.FileReader

actual val isFilePickerSupported: Boolean = true

@Composable
actual fun rememberFilePicker(onPicked: (PickedFile) -> Unit): () -> Unit {
    val current by rememberUpdatedState(onPicked)
    return remember {
        { openFilePicker { current(it) } }
    }
}

/**
 * Append a hidden `<input type="file">`, programmatically click it, and on
 * change read the first selected file fully into memory. The input is
 * removed from the DOM after the read finishes (success or empty pick).
 */
private fun openFilePicker(onResult: (PickedFile) -> Unit) {
    val input = document.createElement("input") as HTMLInputElement
    input.type = "file"
    input.style.display = "none"
    document.body?.appendChild(input)

    input.onchange = { _ ->
        val file = input.files?.item(0)
        if (file == null) {
            document.body?.removeChild(input)
        } else {
            readArrayBuffer(file) { bytes ->
                // File.size is a JsNumber in Wasm interop; go via Double.
                onResult(PickedFile(file.name, file.size.toDouble().toLong(), bytes))
                document.body?.removeChild(input)
            }
        }
    }
    input.click()
}

private fun readArrayBuffer(file: File, onLoaded: (ByteArray) -> Unit) {
    val reader = FileReader()
    reader.onload = onload@{ _ ->
        val arr = reader.result as? ArrayBuffer ?: return@onload
        val view = Int8Array(arr)
        val bytes = ByteArray(view.length) { view[it] }
        onLoaded(bytes)
    }
    reader.readAsArrayBuffer(file)
}
