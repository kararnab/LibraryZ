package com.libraryz.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

actual val isFilePickerSupported: Boolean = true

@Composable
actual fun rememberFilePicker(onPicked: (PickedFile) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    return remember(onPicked) {
        {
            scope.launch(Dispatchers.IO) {
                val dialog = FileDialog(null as Frame?, "Choose a file", FileDialog.LOAD)
                dialog.isVisible = true
                val dir = dialog.directory
                val name = dialog.file
                if (dir != null && name != null) {
                    val f = File(dir, name)
                    val bytes = f.readBytes()
                    onPicked(PickedFile(f.name, f.length(), bytes))
                }
            }
        }
    }
}
