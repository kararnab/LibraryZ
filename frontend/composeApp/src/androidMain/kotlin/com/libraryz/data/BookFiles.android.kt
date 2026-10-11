package com.libraryz.data

import com.libraryz.data.api.AndroidContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Saved books under app-private `filesDir/books`. */
actual fun createBookFiles(): BookFiles? = DirBookFiles(File(AndroidContextHolder.appContext.filesDir, "books"))

class DirBookFiles(private val dir: File) : BookFiles {
    override suspend fun read(name: String): ByteArray? = withContext(Dispatchers.IO) {
        File(dir, name).takeIf { it.isFile }?.readBytes()
    }

    override suspend fun write(name: String, bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val tmp = File(dir, "$name.tmp")
            tmp.writeBytes(bytes)
            Files.move(tmp.toPath(), File(dir, name).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    override suspend fun delete(name: String) {
        withContext(Dispatchers.IO) { File(dir, name).delete() }
    }
}
