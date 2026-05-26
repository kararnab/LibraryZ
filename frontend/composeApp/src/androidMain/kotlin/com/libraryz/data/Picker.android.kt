package com.libraryz.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

actual val isFilePickerSupported: Boolean = true

@Composable
actual fun rememberFilePicker(onPicked: (PickedFile) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) {
                    val displayName = context.queryDisplayName(uri) ?: "file"
                    onPicked(PickedFile(displayName, bytes.size.toLong(), bytes))
                }
            }
        }
    }
    return remember(launcher) {
        { launcher.launch(arrayOf("*/*")) }
    }
}

private fun Context.queryDisplayName(uri: Uri): String? {
    val proj = arrayOf(OpenableColumns.DISPLAY_NAME)
    contentResolver.query(uri, proj, null, null, null)?.use { c ->
        if (c.moveToFirst()) return c.getString(0)
    }
    return uri.lastPathSegment
}
