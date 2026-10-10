package com.libraryz.ui

import com.libraryz.data.DeepLink
import com.libraryz.data.User
import com.libraryz.data.api.ApiException
import com.libraryz.data.parseDeepLink
import com.libraryz.ui.screens.RecoveryProblem
import com.libraryz.ui.screens.classifyRecoveryError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecoveryTest {

    @Test
    fun parsesEmailedLinks() {
        assertEquals(DeepLink.ResetPassword("abc123"), parseDeepLink("https://library.example.org/reset-password?token=abc123"))
        assertEquals(DeepLink.VerifyEmail("x y/é"), parseDeepLink("libraryz://verify-email/?token=x%20y%2F%C3%A9#top"))
        assertEquals(DeepLink.ResetPassword("t"), parseDeepLink("http://localhost:8080/app/reset-password?utm=1&token=t"))
    }

    @Test
    fun ignoresOtherUrls() {
        assertNull(parseDeepLink("http://localhost:8080/"))
        assertNull(parseDeepLink("https://x/reset-password"))
        assertNull(parseDeepLink("https://x/reset-password?token="))
        assertNull(parseDeepLink("https://x/somewhere?token=abc"))
    }

    @Test
    fun recoveryErrorsFollowTheContract() {
        fun api(s: Int) = ApiException(s, "", "x")
        assertEquals(RecoveryProblem.Throttled, classifyRecoveryError(api(429)))
        assertEquals(RecoveryProblem.InvalidLink, classifyRecoveryError(api(410)))
        assertEquals(RecoveryProblem.Other, classifyRecoveryError(api(500)))
        assertEquals(RecoveryProblem.Network, classifyRecoveryError(RuntimeException("offline")))
    }

    @Test
    fun emailCountsAsVerifiedUntilTheBackendSaysOtherwise() {
        val json = Json { ignoreUnknownKeys = true; namingStrategy = JsonNamingStrategy.SnakeCase }
        val old = json.decodeFromString(User.serializer(), """{"id":1,"email":"a@b.c","name":"A","is_moderator":false}""")
        assertTrue(old.emailVerified)
        val unverified = json.decodeFromString(User.serializer(), """{"id":1,"email":"a@b.c","name":"A","is_moderator":false,"email_verified":false}""")
        assertFalse(unverified.emailVerified)
    }
}

class CodeInputTest {
    @Test
    fun acceptsACodeOrAWholeLink() {
        assertEquals("abc_DEF-123", com.libraryz.ui.screens.codeFromInput("  abc_DEF-123 \n"))
        assertEquals("tok123", com.libraryz.ui.screens.codeFromInput("http://localhost:8081/reset-password?token=tok123"))
        assertEquals("v-9", com.libraryz.ui.screens.codeFromInput("https://library.example.org/verify-email?token=v-9"))
        assertEquals("", com.libraryz.ui.screens.codeFromInput("   "))
    }
}
