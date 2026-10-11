package com.libraryz.data.api

import com.libraryz.nav.Navigator
import com.libraryz.nav.Screen
import com.libraryz.nav.needsConnection
import com.libraryz.ui.components.offlineMessage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReachabilityTest {

    @Test
    fun followsWhetherTheServerAnswers() = runTest {
        var up = false
        val api = ApiClient("http://test", engine = MockEngine {
            if (!up) throw kotlinx.io.IOException("connection refused")
            respond("", HttpStatusCode.InternalServerError)
        })
        assertTrue(api.reachable)
        assertFalse(api.ping())
        assertFalse(api.reachable)
        // Any answer, even an error, means it's back.
        up = true
        assertFalse(api.ping())
        assertTrue(api.reachable)
    }

    @Test
    fun guardKeepsTheAppWhereItIs() {
        val nav = Navigator(Screen.Browse)
        var online = false
        nav.guard = { s -> needsConnection(s) == null || online }
        nav.push(Screen.Upload())
        nav.replace(Screen.ContributionQueue)
        assertEquals(Screen.Browse, nav.current)
        nav.push(Screen.Library)
        assertEquals(Screen.Library, nav.current)
        online = true
        nav.push(Screen.EditWork("w1"))
        assertEquals(Screen.EditWork("w1"), nav.current)
    }

    @Test
    fun onlyOnlineScreensNeedAConnection() {
        assertNotNull(needsConnection(Screen.Upload("w1")))
        assertNotNull(needsConnection(Screen.ForYou))
        assertNull(needsConnection(Screen.Library))
        assertNull(needsConnection(Screen.WorkDetail("w1")))
        assertNull(needsConnection(Screen.Preview("e", "PDF", "w", "T")))
        assertEquals("Offline. Books on this device still open.", offlineMessage(0))
        assertTrue("1 change will" in offlineMessage(1))
        assertTrue("3 changes will" in offlineMessage(3))
    }
}
