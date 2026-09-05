package com.layer.core.config

import org.junit.Assert.assertEquals
import org.junit.Test

class RealityPublicKeyTest {
    @Test
    fun urlSafeUnpaddedKeyIsUnchanged() {
        // Public example from sing-box docs (RawURLEncoding, 32-byte key).
        val key = "jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0"
        assertEquals(key, RealityPublicKey.forSingBox(key))
    }

    @Test
    fun standardBase64IsConvertedToRawUrl() {
        assertEquals("abc-de_fg", RealityPublicKey.forSingBox("abc+de/fg=="))
        assertEquals("abc-de_fg", RealityPublicKey.forSingBox("abc de/fg=="))
    }

    @Test
    fun blankStaysBlank() {
        assertEquals("", RealityPublicKey.forSingBox("  "))
    }
}
