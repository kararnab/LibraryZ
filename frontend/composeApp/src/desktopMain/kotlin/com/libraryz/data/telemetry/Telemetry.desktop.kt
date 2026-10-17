package com.libraryz.data.telemetry

import com.libraryz.data.api.FileTokenStore
import com.libraryz.data.api.TokenStore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private fun libraryzFile(name: String) = File(System.getProperty("user.home"), ".libraryz/$name")

actual fun createTelemetryStore(): TokenStore = FileTokenStore(libraryzFile("telemetry.json"))

actual fun createCrashStore(): SyncStore? = FileSyncStore(libraryzFile("crash-reports.json"))

actual fun createEventStore(): SyncStore? = FileSyncStore(libraryzFile("telemetry-events.json"))

actual val telemetryPlatform: String = "desktop"

actual fun telemetryOs(): String = System.getProperty("os.name").orEmpty().let {
    when {
        it.startsWith("Windows") -> "Windows"
        it.startsWith("Mac") -> "macOS"
        it.startsWith("Linux") -> "Linux"
        else -> "Other"
    }
}

@Volatile private var crashCallback: ((String, List<String>) -> Unit)? = null
private var crashHandlerInstalled = false

// One fatal crash per run: the app is going down, and one crash can reach
// these handlers more than once (a crash in a click was reported twice
// without this: Compose's window handler and the default handler).
private val fatalReported = AtomicBoolean(false)

private fun reportFatal(e: Throwable) {
    if (fatalReported.compareAndSet(false, true)) {
        runCatching { crashCallback?.invoke(exceptionType(e), stackFrames(e)) }
    }
}

// AWT's event thread hands its uncaught exceptions to the default handler too.
actual fun installCrashHandler(onCrash: (type: String, frames: List<String>) -> Unit) {
    crashCallback = onCrash
    synchronized(::crashHandlerInstalled) {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
    }
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, e ->
        reportFatal(e)
        if (previous != null) previous.uncaughtException(thread, e) else e.printStackTrace()
    }
}

/**
 * A crash Compose caught itself (an exception in composition, layout or an
 * event handler on the window): it never reaches the default handler, so
 * Main.kt's window exception handler reports it here.
 */
fun reportUiCrash(e: Throwable) = reportFatal(e)

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
