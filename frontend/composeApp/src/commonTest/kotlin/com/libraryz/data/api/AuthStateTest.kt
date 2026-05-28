package com.libraryz.data.api

import com.libraryz.data.User
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthStateTest {

    @Test
    fun bootstrapWithSavedTokenRestoresSession() = runTest {
        val store = FakeTokenStore(initial = "x.y.z")
        val auth = AuthState(store)

        assertFalse(auth.bootstrapped)
        assertFalse(auth.isAuthenticated)

        auth.bootstrap()

        assertTrue(auth.bootstrapped)
        assertTrue(auth.isAuthenticated)
        assertEquals("x.y.z", auth.token)
    }

    @Test
    fun bootstrapWithNoSavedTokenStaysUnauth() = runTest {
        val store = FakeTokenStore()
        val auth = AuthState(store)

        auth.bootstrap()

        assertTrue(auth.bootstrapped)
        assertFalse(auth.isAuthenticated)
        assertNull(auth.token)
    }

    @Test
    fun bootstrapIsIdempotent() = runTest {
        val store = FakeTokenStore(initial = "tok")
        val auth = AuthState(store)

        auth.bootstrap()
        store.stored = "changed-by-someone-else"
        auth.bootstrap()

        // second bootstrap was a no-op; in-memory session still has the
        // original token, not the externally-mutated store value.
        assertEquals("tok", auth.token)
    }

    @Test
    fun signInPersistsAndUpdatesSession() = runTest {
        val store = FakeTokenStore()
        val auth = AuthState(store)

        auth.signIn(Session("token-1"))

        assertEquals("token-1", store.stored)
        assertEquals(1, store.saveCount)
        assertTrue(auth.isAuthenticated)
        assertEquals("token-1", auth.token)
    }

    @Test
    fun clearWipesMemoryAndStore() = runTest {
        val store = FakeTokenStore(initial = "x")
        val auth = AuthState(store)
        auth.bootstrap()

        auth.clear()

        assertFalse(auth.isAuthenticated)
        assertNull(auth.token)
        assertNull(store.stored)
        assertEquals(1, store.clearCount)
    }

    @Test
    fun bootstrapWithSavedTokenPopulatesUserViaFetcher() = runTest {
        val store = FakeTokenStore(initial = "tok")
        val auth = AuthState(store)
        val mod = User(id = 1, email = "m@x.com", name = "M", isModerator = true)
        auth.setUserFetcher { mod }

        auth.bootstrap()

        assertTrue(auth.isAuthenticated)
        assertEquals(mod, auth.user)
        assertTrue(auth.isModerator)
    }

    @Test
    fun bootstrapWithoutSavedTokenLeavesUserNull() = runTest {
        val store = FakeTokenStore()
        val auth = AuthState(store)
        var fetcherCalls = 0
        auth.setUserFetcher {
            fetcherCalls++
            User(id = 1, email = "x", name = "x", isModerator = false)
        }

        auth.bootstrap()

        assertNull(auth.user)
        assertEquals(0, fetcherCalls, "fetcher should not be called when there's no saved token")
    }

    @Test
    fun bootstrapSwallowsFetcherFailureButKeepsSession() = runTest {
        val store = FakeTokenStore(initial = "tok")
        val auth = AuthState(store)
        auth.setUserFetcher { throw RuntimeException("offline") }

        auth.bootstrap()

        assertTrue(auth.isAuthenticated, "session should survive a fetcher failure")
        assertNull(auth.user)
        assertFalse(auth.isModerator)
    }

    @Test
    fun signInPopulatesUserViaFetcher() = runTest {
        val store = FakeTokenStore()
        val auth = AuthState(store)
        val u = User(id = 5, email = "u@x.com", name = "U", isModerator = false)
        auth.setUserFetcher { u }

        auth.signIn(Session("t"))

        assertEquals(u, auth.user)
        assertFalse(auth.isModerator)
    }

    @Test
    fun clearAlsoWipesUser() = runTest {
        val store = FakeTokenStore()
        val auth = AuthState(store)
        auth.setUserFetcher {
            User(id = 9, email = "u@x.com", name = "U", isModerator = true)
        }
        auth.signIn(Session("t"))
        assertEquals(true, auth.user?.isModerator)

        auth.clear()

        assertNull(auth.user)
        assertFalse(auth.isModerator)
    }
}
