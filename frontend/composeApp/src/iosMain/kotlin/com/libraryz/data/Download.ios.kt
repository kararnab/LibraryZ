package com.libraryz.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.writeToFile

// Drafted, not yet compiled — iosMain only links on macOS. Verify on a Mac.
//
// Writes into the app's Documents directory, which surfaces in the Files app
// when Info.plist sets UIFileSharingEnabled + LSSupportsOpeningDocumentsInPlace
// = YES. A share-sheet (UIActivityViewController) flow is the natural follow-up
// if we'd rather let the user pick the destination.
actual val isDownloadSupported: Boolean = true

actual suspend fun saveDownload(name: String, bytes: ByteArray): String =
    withContext(Dispatchers.Default) {
        val docs = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory, NSUserDomainMask, true,
        ).firstOrNull() as? String ?: error("no Documents directory")

        val path = "$docs/$name"
        val ok = bytes.toNSData().writeToFile(path, atomically = true)
        if (!ok) error("failed to write $path")
        // The raw sandbox path is opaque to the user; show a friendlier hint.
        "Files app › On My iPhone › LibraryZ ($name)"
    }
