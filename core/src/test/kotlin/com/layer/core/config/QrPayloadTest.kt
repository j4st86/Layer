package com.layer.core.config

import org.junit.Assert.assertEquals
import org.junit.Test

class QrPayloadTest {
    @Test
    fun keepsHttpsSubscriptionUrl() {
        assertEquals(
            "https://sub.example.com/token",
            QrPayload.extract("https://sub.example.com/token"),
        )
    }

    @Test
    fun picksVlessLineFromMultilineQr() {
        val raw = """
            Layer
            vless://11111111-2222-3333-4444-555555555555@example.com:443?security=tls#Node
        """.trimIndent()
        assertEquals(
            "vless://11111111-2222-3333-4444-555555555555@example.com:443?security=tls#Node",
            QrPayload.extract(raw),
        )
    }

    @Test
    fun prefersVlessOverHttpOnSameCard() {
        val raw = """
            https://example.com
            vless://11111111-2222-3333-4444-555555555555@host:443
        """.trimIndent()
        assertEquals(
            "vless://11111111-2222-3333-4444-555555555555@host:443",
            QrPayload.extract(raw),
        )
    }
}
