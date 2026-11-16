package com.libraryz.ui.screens

import com.libraryz.data.api.ApiException
import kotlin.test.Test
import kotlin.test.assertEquals

class ChangePasswordTest {
    private val describe: (Throwable) -> String = { "described" }

    @Test
    fun statusesMapToTheirProblems() {
        assertEquals(ChangePasswordProblem.WrongCurrent, classifyChangePasswordError(ApiException(403, "current password is incorrect", "x"), describe))
        assertEquals(
            ChangePasswordProblem.Policy("Password must be at least 8 characters"),
            classifyChangePasswordError(ApiException(400, "password must be at least 8 characters", "x"), describe),
        )
        assertEquals(ChangePasswordProblem.Throttled(120), classifyChangePasswordError(ApiException(429, "", "x", retryAfterSeconds = 120), describe))
        assertEquals(ChangePasswordProblem.Other("described"), classifyChangePasswordError(ApiException(503, "", "x"), describe))
        assertEquals(ChangePasswordProblem.Other("described"), classifyChangePasswordError(RuntimeException("io"), describe))
    }

    @Test
    fun retryPhraseRoundsUpToMinutes() {
        assertEquals("in a few minutes", retryPhrase(null))
        assertEquals("in 1 second", retryPhrase(1))
        assertEquals("in 40 seconds", retryPhrase(40))
        assertEquals("in 1 minute", retryPhrase(60))
        assertEquals("in 2 minutes", retryPhrase(61))
        assertEquals("in 15 minutes", retryPhrase(900))
    }
}
