package com.layer.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSanitizerTest {
    @Test
    fun redactsUuid() {
        val raw = """uuid=11111111-2222-3333-4444-555555555555 failed"""
        val sanitized = LogSanitizer.sanitize(raw)
        assertFalse(sanitized.contains("11111111"))
        assertEquals(true, sanitized.contains("[redacted]"))
    }

    @Test
    fun stripsAnsiColorCodes() {
        val raw = "\u001B[31mERROR\u001B[0m UDP is not supported by outbound: proxy"
        assertEquals("ERROR UDP is not supported by outbound: proxy", LogSanitizer.sanitize(raw))
    }

    @Test
    fun redactsRealityPublicKey() {
        val raw = """"public_key": "dGVzdC1wdWJsaWMta2V5LWZvci11bml0LXRlc3Rz""""
        val sanitized = LogSanitizer.sanitize(raw)
        assertFalse(sanitized.contains("dGVzdC1wdWJsaWMta2V5"))
        assertTrue(sanitized.contains("[redacted]"))
    }

    @Test
    fun rewritesLegacyPixelnetBrand() {
        assertEquals("libbox 1.14.0-layer", LogSanitizer.sanitize("libbox 1.14.0-pixelnet"))
    }
}
