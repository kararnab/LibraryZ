package com.libraryz.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual val isDownloadSupported: Boolean = true

actual suspend fun saveDownload(name: String, bytes: ByteArray): String =
    withContext(Dispatchers.IO) {
        val home = System.getProperty("user.home")
        val dir = File(home, "Downloads").also { if (!it.exists()) it.mkdirs() }
        val file = File(dir, name)
        file.writeBytes(bytes)
        file.absolutePath
    }
