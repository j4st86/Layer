package com.layer.core.config

import org.junit.Assert.assertEquals
import org.junit.Test

class RealityPublicKeyTest {
    @Test
    fun urlSafeUnpaddedKeyIsUnchanged() {
        // Dummy 32-byte all-zero key in unpadded URL-safe Base64.
        val key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
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
