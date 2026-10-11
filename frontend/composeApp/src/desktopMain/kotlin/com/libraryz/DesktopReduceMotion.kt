package com.libraryz

import java.util.concurrent.TimeUnit

/**
 * The desktop's "reduce motion" accessibility setting, read once at start.
 * GNOME turns animations off with `enable-animations`; macOS has Reduce
 * motion in Accessibility › Display. Elsewhere (Windows, other Linux
 * desktops) motion stays on.
 */
object DesktopReduceMotion {
    val enabled: Boolean by lazy {
        val os = System.getProperty("os.name").orEmpty()
        when {
            os.startsWith("Linux") ->
                run("gsettings", "get", "org.gnome.desktop.interface", "enable-animations")?.trim() == "false"
            os.startsWith("Mac") ->
                run("defaults", "read", "com.apple.universalaccess", "reduceMotion")?.trim() == "1"
            else -> false
        }
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
