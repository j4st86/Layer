package com.layer.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutableDnsAnswersTest {
    @Test
    fun dropsClashFakeIpAndKeepsARealAddress() {
        assertEquals(
            listOf("142.251.1.188"),
            RoutableDnsAnswers.usable(listOf("198.18.0.3", "142.251.1.188")),
        )
        assertTrue(RoutableDnsAnswers.isUnusable("198.19.255.1"))
        assertTrue(RoutableDnsAnswers.isUnusable("127.0.0.1"))
        assertTrue(RoutableDnsAnswers.isUnusable("0.0.0.0"))
        assertFalse(RoutableDnsAnswers.isUnusable("8.8.8.8"))
        assertFalse(RoutableDnsAnswers.isUnusable("2001:4860:4860::8888"))
    }
}
