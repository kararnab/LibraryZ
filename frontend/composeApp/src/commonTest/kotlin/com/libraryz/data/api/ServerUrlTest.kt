package com.libraryz.data.api

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerUrlTest {
    @Test
    fun acceptsTheUsualServers() {
        mapOf(
            "http://localhost:8080" to "http://localhost:8080",
            "http://10.0.2.2:8080/" to "http://10.0.2.2:8080",
            "  192.168.1.20:8080 " to "http://192.168.1.20:8080",
            "HTTPS://Library.Example.org" to "https://library.example.org",
            "https://example.org/libraryz/api//" to "https://example.org/libraryz/api",
            "http://[::1]:8080" to "http://[::1]:8080",
            "localhost" to "http://localhost",
        ).forEach { (input, want) -> assertEquals(want, parseServerUrl(input), input) }
    }

    @Test
    fun rejectsWhatIsNotAServer() {
        listOf(
            "", "   ", "ftp://example.org", "http://", "http://:8080", "http://host:0", "http://host:65536",
            "http://host:80a", "http://host:+80", "http://256.1.1.1", "http://1.2.3", "http://-bad.example",
            "http://under_score.example", "http://user@host", "http://host/a?b=c", "http://host/#x",
            "http://ho st", "http:/host", "http://[zz::1]", "http://[::1",
        ).forEach { assertNull(parseServerUrl(it), it) }
    }

    @Test
    fun storeKeepsTheChoiceAndDropsWhatNoLongerParses() = runTest {
        val disk = FakeTokenStore()
        val store = ServerUrlStore { disk }
        store.save("http://192.168.1.20:8080")
        assertEquals("http://192.168.1.20:8080", ServerUrlStore { disk }.load())
        store.save(null)
        assertNull(store.load())
        assertNull(ServerUrlStore { FakeTokenStore("not a url") }.load())
        assertNull(ServerUrlStore { error("no disk") }.load())
    }

    @Test
    fun devServersAreLoopbackOrLocalNetwork() {
        listOf(
            "http://localhost:8080", "http://127.0.0.1:8080", "http://[::1]:8080", "http://10.0.2.2:8080",
            "http://192.168.0.5:8080", "http://nas.local",
        ).forEach { assertTrue(isDevServerUrl(it), it) }
        listOf(
            "https://library.example.org", "http://127.example.org", "http://8.8.8.8", "http://[2001:db8::1]", "nonsense url",
        ).forEach { assertFalse(isDevServerUrl(it), it) }
    }
}
