package com.libraryz.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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

actual fun createTokenStore(): TokenStore =
    FileTokenStore(File(System.getProperty("user.home"), ".libraryz/token"))
