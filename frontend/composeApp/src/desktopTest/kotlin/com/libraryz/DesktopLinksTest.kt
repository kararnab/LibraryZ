package com.libraryz

import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopLinksTest {
    private val dir: File = Files.createTempDirectory("libraryz-instance").toFile()
    private val opened = mutableListOf<SingleInstance>()

    @AfterTest
    fun cleanUp() {
        opened.forEach(SingleInstance::close)
        dir.deleteRecursively()
    }

    private fun instance(name: String = "instance") = SingleInstance(dir, name).also { opened += it }

    @Test
    fun aSecondLaunchHandsItsLinkToTheFirst() {
        val received = LinkedBlockingQueue<String>()
        assertTrue(instance().claim { received += it })

        val second = instance()
        assertFalse(second.claim { error("the second copy must not listen") })
        assertTrue(second.forward("libraryz://reset-password?token=t"))
        assertEquals("libraryz://reset-password?token=t", received.poll(5, TimeUnit.SECONDS))
        assertTrue(second.forward("")) // a plain second launch: just come forward
        assertEquals("", received.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun ignoresMessagesWithoutTheKey() {
        val received = LinkedBlockingQueue<String>()
        assertTrue(instance().claim { received += it })
        val port = File(dir, "instance.port").readText().trim().substringBefore(' ').toInt()
        Socket(InetAddress.getLoopbackAddress(), port).use {
            it.getOutputStream().write("wrong libraryz://reset-password?token=t\n".encodeToByteArray())
        }
        assertNull(received.poll(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun theKeyFileIsOwnerOnly() {
        assertTrue(instance().claim {})
        val perms = Files.getPosixFilePermissions(File(dir, "instance.port").toPath()).map { it.name }.toSet()
        assertEquals(setOf("OWNER_READ", "OWNER_WRITE"), perms)
    }

    @Test
    fun separateNamesDontShareALock() {
        assertTrue(instance("instance").claim {})
        assertTrue(instance("instance-dev").claim {})
    }

    @Test
    fun noHolderMeansNothingToForwardTo() {
        assertFalse(instance().forward("libraryz://verify-email?token=t"))
    }

    @Test
    fun linuxHandlerQuotesThePath() {
        val entry = linuxHandlerEntry("/opt/library z/bin/Library\"Z$")
        assertTrue("Exec=\"/opt/library z/bin/Library\\\"Z\\$\" %u\n" in entry, entry)
        assertTrue("MimeType=x-scheme-handler/libraryz;\n" in entry)
        assertTrue("NoDisplay=true\n" in entry)
    }
}
