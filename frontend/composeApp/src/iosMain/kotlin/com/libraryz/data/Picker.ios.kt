package com.libraryz.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject

// Drafted, not yet compiled — iosMain only links on macOS. Verify on a Mac.
//
// Presents a UIDocumentPickerViewController over the root view controller and
// reads the chosen file into a PickedFile. The delegate is held in a remember
// slot so ARC doesn't reclaim it while the picker is on screen.
actual val isFilePickerSupported: Boolean = true

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberFilePicker(onPicked: (PickedFile) -> Unit): () -> Unit {
    // Strong ref kept across recompositions; replaced each time we launch.
    val holder = remember { arrayOfNulls<PickerDelegate>(1) }
    return remember(onPicked) {
        {
            val delegate = PickerDelegate(onPicked)
            holder[0] = delegate

            // initForOpeningContentTypes: accepts any document (UTTypeItem).
            val picker = UIDocumentPickerViewController(
                forOpeningContentTypes = listOf(UTTypeItem),
            ).apply {
                this.delegate = delegate
                allowsMultipleSelection = false
            }

            topViewController()?.presentViewController(picker, animated = true, completion = null)
        }
    }
}

// Resolve the foreground window's root VC. keyWindow is deprecated on iOS 13+;
// the windows list is the pragmatic single-scene replacement for v0.
@OptIn(ExperimentalForeignApi::class)
private fun topViewController() =
    UIApplication.sharedApplication.windows.firstOrNull()
        ?.let { (it as platform.UIKit.UIWindow).rootViewController }

@OptIn(ExperimentalForeignApi::class)
private class PickerDelegate(
    private val onPicked: (PickedFile) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
        // The URL is security-scoped; must bracket the read in start/stop.
        val scoped = url.startAccessingSecurityScopedResource()
        try {
            val data = NSData.create(contentsOfURL = url) ?: return
            val name = url.lastPathComponent ?: "file"
            onPicked(PickedFile(name, data.length.toLong(), data.toByteArray()))
        } finally {
            if (scoped) url.stopAccessingSecurityScopedResource()
        }
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        // No-op: leaving the launcher's onPicked uncalled is the cancel signal.
    }
}
