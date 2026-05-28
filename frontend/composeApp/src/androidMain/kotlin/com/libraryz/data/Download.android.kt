package com.libraryz.data

import android.os.Environment
import com.libraryz.data.api.AndroidContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual val isDownloadSupported: Boolean = true

actual suspend fun saveDownload(name: String, bytes: ByteArray): String =
    withContext(Dispatchers.IO) {
        val ctx = AndroidContextHolder.appContext
        // App-scoped external storage — no runtime permission required and
        // user can still find files via a file manager at
        // /sdcard/Android/data/<pkg>/files/Download/. Good enough for v0;
        // graduate to MediaStore.Downloads later for a true Downloads/ entry.
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(ctx.filesDir, "downloads").apply { mkdirs() }
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, name)
        file.writeBytes(bytes)
        file.absolutePath
    }
