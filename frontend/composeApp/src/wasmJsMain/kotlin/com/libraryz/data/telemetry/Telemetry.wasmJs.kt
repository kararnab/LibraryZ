package com.libraryz.data.telemetry

import com.libraryz.data.api.LocalStorageTokenStore
import com.libraryz.data.api.TokenStore
import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.w3c.dom.get

actual fun createTelemetryStore(): TokenStore = LocalStorageTokenStore("libraryz_telemetry")

actual fun createCrashStore(): SyncStore? = LocalStorageSyncStore("libraryz_crash_reports")

actual fun createEventStore(): SyncStore? = LocalStorageSyncStore("libraryz_telemetry_events")

private class LocalStorageSyncStore(private val key: String) : SyncStore {
    override fun read(): String? = localStorage[key]
    override fun write(value: String) = localStorage.setItem(key, value)
    override fun clear() = localStorage.removeItem(key)
}

actual val telemetryPlatform: String = "web"

// The browser's user agent would say far more than the server needs.
actual fun telemetryOs(): String = "Web"

/** "<error name>\n<stack>" of an error event's error or a rejection's reason. */
@JsFun(
    """(e) => {
        const x = e && e.error !== undefined ? e.error : (e && e.reason !== undefined ? e.reason : e);
        const name = x && x.name ? String(x.name) : 'Error';
        return name + '\n' + (x && x.stack ? String(x.stack) : '');
    }""",
)
private external fun describeJsError(e: JsAny?): String

private var crashCallback: ((String, List<String>) -> Unit)? = null
private var crashHandlerInstalled = false

actual fun installCrashHandler(onCrash: (type: String, frames: List<String>) -> Unit) {
    crashCallback = onCrash
    if (crashHandlerInstalled) return
    crashHandlerInstalled = true
    val report = { e: JsAny? ->
        val text = describeJsError(e)
        val type = text.substringBefore('\n').substringBefore(':').trim()
        // Chrome: "    at fn (url:1:2)"; Firefox and Safari: "fn@url:1:2".
        val frames = text.substringAfter('\n').lineSequence().map { it.trim() }.mapNotNull { line ->
            when {
                line.startsWith("at ") -> scrubFrame(line)
                '@' in line -> scrubFrame("at $line")
                else -> null
            }
        }.toList()
        runCatching { crashCallback?.invoke(type, frames) }
    }
    window.addEventListener("error", { e -> report(e) })
    window.addEventListener("unhandledrejection", { e -> report(e) })
}
