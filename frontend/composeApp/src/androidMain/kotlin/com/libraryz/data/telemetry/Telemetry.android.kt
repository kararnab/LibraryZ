package com.libraryz.data.telemetry

import android.os.Build
import com.libraryz.data.api.AndroidContextHolder
import com.libraryz.data.api.FileTokenStore
import com.libraryz.data.api.TokenStore
import java.io.File

actual fun createTelemetryStore(): TokenStore =
    FileTokenStore(File(AndroidContextHolder.appContext.filesDir, "telemetry.json"))

actual fun createCrashStore(): SyncStore? =
    FileSyncStore(File(AndroidContextHolder.appContext.filesDir, "crash_reports.json"))

actual fun createEventStore(): SyncStore? =
    FileSyncStore(File(AndroidContextHolder.appContext.filesDir, "telemetry_events.json"))

actual val telemetryPlatform: String = "android"

actual fun telemetryOs(): String = "Android " + Build.VERSION.RELEASE.substringBefore('.')

@Volatile private var crashCallback: ((String, List<String>) -> Unit)? = null
private var crashHandlerInstalled = false

actual fun installCrashHandler(onCrash: (type: String, frames: List<String>) -> Unit) {
    crashCallback = onCrash
    synchronized(::crashHandlerInstalled) {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
    }
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, e ->
        runCatching { crashCallback?.invoke(exceptionType(e), stackFrames(e)) }
        previous?.uncaughtException(thread, e)
    }
}

private class FileSyncStore(private val file: File) : SyncStore {
    override fun read(): String? = file.takeIf { it.exists() }?.readText()
    override fun write(value: String) {
        file.parentFile?.mkdirs()
        file.writeText(value)
    }
    override fun clear() {
        file.delete()
    }
}
