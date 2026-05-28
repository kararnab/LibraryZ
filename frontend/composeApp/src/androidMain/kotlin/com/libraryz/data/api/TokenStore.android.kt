package com.libraryz.data.api

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Set by MainActivity before App() composes. Crude but avoids dragging in
 * androidx.startup or Hilt just to find files-dir from a multiplatform
 * factory.
 */
object AndroidContextHolder {
    @Volatile
    lateinit var appContext: Context
}

class FileTokenStore(private val file: File) : TokenStore {
    override suspend fun load(): String? = withContext(Dispatchers.IO) {
        if (file.exists()) file.readText().trim().ifEmpty { null } else null
    }
    override suspend fun save(token: String) {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.writeText(token)
        }
    }
    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            if (file.exists()) file.delete()
        }
    }
}

actual fun createTokenStore(): TokenStore {
    val ctx = AndroidContextHolder.appContext
    return FileTokenStore(File(ctx.filesDir, "libraryz_token"))
}
