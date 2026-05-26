package com.libraryz.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

// NSData <-> ByteArray bridges shared by the iOS file picker, download, and PDF
// actuals. Both copy (dataWithBytes / memcpy), so the pinned Kotlin array and
// the NSData have independent lifetimes once the call returns.
//
// NOTE: this file is part of the iosMain source set, which only links on a
// macOS host. It has not been compiled on this Linux machine — verify on a Mac.

@OptIn(ExperimentalForeignApi::class)
fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}

@OptIn(ExperimentalForeignApi::class)
fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val out = ByteArray(len)
    out.usePinned { pinned ->
        memcpy(pinned.addressOf(0), this.bytes, length)
    }
    return out
}
