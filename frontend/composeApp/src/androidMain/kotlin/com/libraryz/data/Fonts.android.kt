package com.libraryz.data

import android.graphics.Typeface
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.libraryz.data.api.AndroidContextHolder
import java.io.File

internal actual fun loadFont(identity: String, bytes: ByteArray, weight: FontWeight, style: FontStyle): Font? =
    runCatching {
        // Android loads fonts from files: one per font, named by its hash, in the cache.
        val file = File(AndroidContextHolder.appContext.cacheDir, "epub-fonts/$identity.ttf")
        if (!file.exists() || file.length() != bytes.size.toLong()) {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
        // Check Android can read it now, so a bad file never reaches text layout.
        if (Typeface.createFromFile(file) == null) return null
        Font(file, weight, style)
    }.getOrNull()
