package com.libraryz.data.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalNetworkTest {
    @Test
    fun privateAndLinkLocalAddressesAreLocal() {
        listOf(
            "http://10.0.2.2:8080", "http://192.168.29.234:8080/", "http://172.16.0.1", "http://172.31.255.255",
            "http://169.254.1.1", "http://nas.local:8080", "http://[fd12::1]:8080", "http://[fe80::1]",
            "http://user@10.1.2.3/path",
        ).forEach { assertTrue(isLocalNetworkUrl(it), it) }
    }

    @Test
    fun loopbackAndPublicHostsAreNot() {
        listOf(
            "http://localhost:8080", "http://127.0.0.1:8080", "http://[::1]:8080", "https://library.example.org",
            "http://172.32.0.1", "http://172.15.0.1", "http://8.8.8.8", "http://10.example.org", "not a url",
        ).forEach { assertFalse(isLocalNetworkUrl(it), it) }
    }
}
