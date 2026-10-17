package com.libraryz.data.telemetry

import com.libraryz.data.api.TokenStore
import com.libraryz.data.api.UserDefaultsTokenStore
import kotlin.experimental.ExperimentalNativeApi
import platform.Foundation.NSUserDefaults

actual fun createTelemetryStore(): TokenStore = UserDefaultsTokenStore("libraryz_telemetry")

actual fun createCrashStore(): SyncStore? = UserDefaultsSyncStore("libraryz_crash_reports")

actual fun createEventStore(): SyncStore? = UserDefaultsSyncStore("libraryz_telemetry_events")

private class UserDefaultsSyncStore(private val key: String) : SyncStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    override fun read(): String? = defaults.stringForKey(key)
    override fun write(value: String) {
        defaults.setObject(value, key)
        // Out at once: a crash report is written as the process ends.
        defaults.synchronize()
    }
    override fun clear() = defaults.removeObjectForKey(key)
}

actual val telemetryPlatform: String = "ios"

actual fun telemetryOs(): String = "iOS"

private var crashCallback: ((String, List<String>) -> Unit)? = null
private var crashHandlerInstalled = false

@OptIn(ExperimentalNativeApi::class)
actual fun installCrashHandler(onCrash: (type: String, frames: List<String>) -> Unit) {
    crashCallback = onCrash
    if (crashHandlerInstalled) return
    crashHandlerInstalled = true
    setUnhandledExceptionHook { e ->
        runCatching { crashCallback?.invoke(exceptionType(e), stackFrames(e)) }
        terminateWithUnhandledException(e)
    }
}
