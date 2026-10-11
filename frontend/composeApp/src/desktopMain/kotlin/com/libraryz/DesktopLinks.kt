package com.libraryz

import com.libraryz.data.AppLinkScheme
import com.libraryz.data.DeepLinkInbox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.Desktop
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * libraryz:// links on the desktop. The OS starts the app with the link as
 * an argument (Linux, Windows) or sends it to the running app (macOS). One
 * copy runs at a time: a second launch passes its link to the first and
 * exits, and the first comes to the front.
 */
internal object DesktopLinks {
    private val dir = File(System.getProperty("user.home"), ".libraryz")

    /** Packaged installs only (jpackage sets it); `./gradlew run` has none. */
    private val appPath: String? = System.getProperty("jpackage.app-path")

    private val _raise = MutableStateFlow(0)

    /** Bumped whenever the window should come to the front. */
    val raise: StateFlow<Int> = _raise

    /**
     * Call first in main. False when another copy took the link: exit then.
     * [args] are the command line; the link is the one starting libraryz:.
     */
    fun start(args: Array<String>): Boolean {
        val link = args.firstOrNull { it.startsWith("$AppLinkScheme:", ignoreCase = true) }
        // Packaged and development copies are separate, so `./gradlew run`
        // works while the installed app is open.
        val instance = SingleInstance(dir, if (appPath != null) "instance" else "instance-dev")
        if (!instance.claim(::receive)) {
            if (instance.forward(link.orEmpty())) return false
            // The other copy didn't answer (it's exiting, or hung): run anyway.
        }
        link?.let(DeepLinkInbox::deliver)
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_URI)) {
            Desktop.getDesktop().setOpenURIHandler { receive(it.uri.toString()) }
        }
        // Before the window opens: GNOME picks the window's entry (and icon) then.
        if (isLinux) registerLinuxIcon(devCommand = if (appPath == null) currentCommand() else null)
        appPath?.let { path -> thread(isDaemon = true, name = "libraryz-scheme") { registerScheme(path) } }
        return true
    }

    private fun receive(message: String) {
        DeepLinkInbox.deliver(message)
        _raise.value++
    }
}

/**
 * At most one process per [name] holds [dir]/[name].lock. The holder listens
 * on a loopback port, written with a random key to [dir]/[name].port
 * (owner-only), and takes one line per connection: "key message".
 */
internal class SingleInstance(private val dir: File, name: String) {
    private val lockFile = File(dir, "$name.lock")
    private val portFile = File(dir, "$name.port")
    private var lock: FileLock? = null
    private var server: ServerSocket? = null

    /** True when this process now holds the lock; [onMessage] gets each message. */
    fun claim(onMessage: (String) -> Unit): Boolean {
        dir.mkdirs()
        val channel = FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val held = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }
        if (held == null) {
            channel.close()
            return false
        }
        lock = held
        val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        server = socket
        val key = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val tmp = File(dir, "${portFile.name}.tmp")
        // Owner-only before the key goes in.
        tmp.delete()
        tmp.createNewFile()
        tmp.setReadable(false, false)
        tmp.setReadable(true, true)
        tmp.setWritable(false, false)
        tmp.setWritable(true, true)
        tmp.writeText("${socket.localPort} $key\n")
        if (!tmp.renameTo(portFile)) {
            portFile.delete()
            tmp.renameTo(portFile)
        }
        thread(isDaemon = true, name = "libraryz-instance") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: continue
                client.use {
                    it.soTimeout = 2_000
                    val line = runCatching { readLine(it, MaxLine) }.getOrNull() ?: return@use
                    if (line.substringBefore(' ') == key) onMessage(line.substringAfter(' ', ""))
                }
            }
        }
        return true
    }

    /** Hands [message] to the holder; false if none answered within ~2 s. */
    fun forward(message: String): Boolean {
        repeat(20) {
            val sent = runCatching {
                val (port, key) = portFile.readText().trim().split(' ', limit = 2)
                Socket(InetAddress.getLoopbackAddress(), port.toInt()).use {
                    it.getOutputStream().write("$key $message\n".encodeToByteArray())
                }
            }.isSuccess
            if (sent) return true
            Thread.sleep(100) // the holder may not have written its port yet
        }
        return false
    }

    /** Stops listening and releases the lock (tests; the OS does it at exit). */
    fun close() {
        server?.close()
        lock?.channel()?.close()
    }

    private fun readLine(socket: Socket, max: Int): String {
        val input = socket.getInputStream()
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() < max) {
            val b = input.read()
            if (b == -1 || b == '\n'.code) break
            bytes.write(b)
        }
        return bytes.toString(Charsets.UTF_8).trimEnd('\r')
    }

    private companion object {
        const val MaxLine = 8192
    }
}

/**
 * Makes the OS open libraryz:// links with the installed app at [appPath].
 * macOS takes it from Info.plist (build.gradle.kts). Best effort: a failure
 * only means links open nothing, and the code still works.
 */
private fun registerScheme(appPath: String) {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    runCatching {
        when {
            os.startsWith("windows") -> registerWindows(appPath)
            os.startsWith("linux") -> registerLinux(appPath)
        }
    }
}

private val isLinux = System.getProperty("os.name").orEmpty().startsWith("Linux")

private val dataHome: String
    get() = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
        ?: File(System.getProperty("user.home"), ".local/share").path

private fun registerLinux(appPath: String) {
    val apps = File(dataHome, "applications")
    val entry = File(apps, LinuxHandlerFile)
    val content = linuxHandlerEntry(appPath)
    if (entry.isFile && entry.readText() == content) return
    apps.mkdirs()
    entry.writeText(content)
    run("xdg-mime", "default", LinuxHandlerFile, "x-scheme-handler/$AppLinkScheme")
    run("update-desktop-database", apps.path)
}

internal const val LinuxHandlerFile = "libraryz-url-handler.desktop"
internal const val LinuxDevFile = "libraryz-dev.desktop"

/** The window's X11 WM_CLASS (Main.kt), which desktop entries match on. */
internal const val LinuxWindowClass = "LibraryZ"

/** A hidden desktop entry that opens libraryz:// links with [appPath]. */
internal fun linuxHandlerEntry(appPath: String): String =
    linuxEntry(listOf(appPath), linuxIcon.path) + "MimeType=x-scheme-handler/$AppLinkScheme;\n"

/**
 * A hidden desktop entry for the window: GNOME (46+) no longer shows a
 * window's own icon, only that of the entry whose StartupWMClass matches.
 */
internal fun linuxEntry(command: List<String>, icon: String, links: Boolean = true): String {
    // Desktop Entry spec: quote each argument; inside quotes, escape " ` $ \,
    // and % (a field code) is written %%.
    val exec = command.joinToString(" ") {
        "\"" + it.replace(Regex("""(["`$\\])"""), """\\$1""").replace("%", "%%") + "\""
    }
    return """
        |[Desktop Entry]
        |Type=Application
        |Name=LibraryZ
        |Exec=$exec${if (links) " %u" else ""}
        |Icon=$icon
        |StartupWMClass=$LinuxWindowClass
        |NoDisplay=true
        |Terminal=false
        |""".trimMargin()
}

/** Where [registerLinuxIcon] puts the icon; entries name the file, not a theme icon. */
private val linuxIcon: File
    get() = File(dataHome, "icons/hicolor/256x256/apps/libraryz.png")

/**
 * Installs the "Lz" icon into the user's icon theme, and for `./gradlew run`
 * ([devCommand], the JVM's command line) a hidden entry pointing at it; an
 * installed app's link handler entry names it already.
 */
private fun registerLinuxIcon(devCommand: List<String>?) {
    runCatching {
        val bytes = object {}.javaClass.getResourceAsStream("/libraryz-icon.png")?.use { it.readBytes() }
            ?: return
        val icon = linuxIcon
        if (!(icon.isFile && icon.readBytes().contentEquals(bytes))) {
            icon.parentFile.mkdirs()
            icon.writeBytes(bytes)
        }
        if (devCommand == null) return
        val entry = File(dataHome, "applications/$LinuxDevFile")
        val content = linuxEntry(devCommand, icon.path, links = false)
        if (entry.isFile && entry.readText() == content) return
        entry.parentFile.mkdirs()
        entry.writeText(content)
    }
}

/**
 * How this JVM was started, to start it again. Not ProcessHandle's info: it
 * leaves out the arguments when they're long (`./gradlew run`'s classpath).
 */
private fun currentCommand(): List<String>? = runCatching {
    File("/proc/self/cmdline").readText().split('\u0000').filter { it.isNotEmpty() }
}.getOrNull()?.takeIf { it.isNotEmpty() }

private fun registerWindows(appPath: String) {
    val key = """HKCU\Software\Classes\$AppLinkScheme"""
    val command = "\"$appPath\" \"%1\""
    val current = runCatching {
        ProcessBuilder("reg", "query", """$key\shell\open\command""", "/ve")
            .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
    }.getOrDefault("")
    if (command in current) return
    run("reg", "add", key, "/ve", "/d", "URL:LibraryZ", "/f")
    run("reg", "add", key, "/v", "URL Protocol", "/d", "", "/f")
    run("reg", "add", """$key\shell\open\command""", "/ve", "/d", command, "/f")
}

private fun run(vararg command: String) {
    runCatching { ProcessBuilder(*command).redirectErrorStream(true).start().waitFor() }
}
