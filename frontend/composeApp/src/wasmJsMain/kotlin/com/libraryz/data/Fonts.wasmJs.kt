package com.libraryz.data

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr

internal actual fun loadFont(identity: String, bytes: ByteArray, weight: FontWeight, style: FontStyle): Font? =
    runCatching {
        // Check Skia can read it now, so a bad file never reaches text layout.
        FontMgr.default.makeFromData(Data.makeFromBytes(bytes)) ?: return null
        Font(identity, bytes, weight, style)
    }.getOrNull()
