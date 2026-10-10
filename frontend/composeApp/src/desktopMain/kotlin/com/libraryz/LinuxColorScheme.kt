package com.libraryz

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

/**
 * The desktop's dark-mode preference on Linux. Skiko only knows the system
 * theme on macOS and Windows, so `isSystemInDarkTheme()` is always false
 * here. Reads the freedesktop appearance portal (GNOME, KDE, and others):
 * `color-scheme` 1 = prefer dark, 0 = no preference, 2 = prefer light.
 * Falls back to GNOME's gsettings, then watches the portal's
 * SettingChanged signal so a switch in Settings applies live. Uses the
 * `gdbus` CLI (part of GLib), which avoids a D-Bus library dependency.
 * [dark] stays null off Linux or when neither source answers, and the
 * theme then falls back to Compose.
 */
object LinuxColorScheme {
    private val _dark = MutableStateFlow<Boolean?>(null)
    val dark: StateFlow<Boolean?> = _dark.asStateFlow()

    private const val PORTAL = "org.freedesktop.portal.Desktop"
    private const val PORTAL_PATH = "/org/freedesktop/portal/desktop"
    private val portalValue = Regex("""uint32 (\d)""")

    fun start() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Linux")) return
        _dark.value = readPortal() ?: readGsettings()
        watch()
    }

    private fun readPortal(): Boolean? = run(
        "gdbus", "call", "--session", "--dest", PORTAL, "--object-path", PORTAL_PATH,
        "--method", "org.freedesktop.portal.Settings.Read",
        "org.freedesktop.appearance", "color-scheme",
    )?.let { out -> portalValue.find(out)?.groupValues?.get(1)?.let { it == "1" } }

    private fun readGsettings(): Boolean? =
        run("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")
            ?.let { it.contains("prefer-dark") }

    /** Follows the portal's SettingChanged signal for color-scheme. */
    private fun watch() {
        val process = runCatching {
            ProcessBuilder(
                "gdbus", "monitor", "--session", "--dest", PORTAL, "--object-path", PORTAL_PATH,
            ).redirectErrorStream(true).start()
        }.getOrNull() ?: return
        Runtime.getRuntime().addShutdownHook(Thread { process.destroy() })
        Thread({
            process.inputStream.bufferedReader().forEachLine { line ->
                if ("SettingChanged" in line && "'org.freedesktop.appearance', 'color-scheme'" in line) {
                    portalValue.find(line)?.let { _dark.value = it.groupValues[1] == "1" }
                }
            }
        }, "libraryz-color-scheme").apply { isDaemon = true }.start()
    }

    private fun run(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroy()
            return null
        }
        if (process.exitValue() != 0) null else process.inputStream.bufferedReader().readText()
    }.getOrNull()
}
