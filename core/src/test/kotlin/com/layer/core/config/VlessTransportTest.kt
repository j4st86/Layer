package com.layer.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlessTransportTest {
    @Test
    fun stripsVisionOnXhttp() {
        assertEquals("", VlessTransport.effectiveFlow("xhttp", "xtls-rprx-vision"))
        assertEquals("", VlessTransport.effectiveFlow("ws", "xtls-rprx-vision"))
        assertEquals("xtls-rprx-vision", VlessTransport.effectiveFlow("tcp", "xtls-rprx-vision"))
    }

    @Test
    fun recognizesXhttpAliases() {
        assertTrue(VlessTransport.isXhttp("splithttp"))
        assertTrue(VlessTransport.isSupported("xhttp"))
        assertFalse(VlessTransport.isSupported("kcp"))
    }
}
