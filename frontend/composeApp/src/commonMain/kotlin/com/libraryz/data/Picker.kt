package com.libraryz.data

import androidx.compose.runtime.Composable

/**
 * One picked file held in memory. Sized for v0 (books are typically <50 MB);
 * if we ever need to support multi-hundred-MB uploads we'll switch to a
 * streaming source instead of `bytes`.
 */
data class PickedFile(
    val name: String,
    val sizeBytes: Long,
    val bytes: ByteArray,
) {
    val prettySize: String get() = formatBytes(sizeBytes)

    @Suppress("RedundantNullableReturnType")
    override fun equals(other: Any?): Boolean =
        other is PickedFile && name == other.name && sizeBytes == other.sizeBytes
    override fun hashCode(): Int = name.hashCode() * 31 + sizeBytes.hashCode()
}

/** False on Wasm + iOS for now — those branches show a NYI snackbar. */
expect val isFilePickerSupported: Boolean

/**
 * Returns a launcher function. Calling the function opens the native file
 * dialog; when the user picks, [onPicked] is invoked with the bytes.
 * No-op on platforms where [isFilePickerSupported] is false.
 */
@Composable
expect fun rememberFilePicker(onPicked: (PickedFile) -> Unit): () -> Unit
