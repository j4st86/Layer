package com.layer.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnConnectionParserTest {
    private val vless =
        "vless://11111111-2222-3333-4444-555555555555@example.com:443?security=tls#Node"

    @Test
    fun acceptsVlessLink() {
        val parsed = VpnConnectionParser.parse(vless).getOrThrow()
        assertTrue(parsed is VpnConnectionInput.VlessLink)
        assertEquals(vless, parsed.raw)
    }

    @Test
    fun acceptsHttpsSubscriptionWithPath() {
        val url = "https://sub.example.com/subass/token"
        val parsed = VpnConnectionParser.parse(url).getOrThrow()
        assertTrue(parsed is VpnConnectionInput.Subscription)
    }

    @Test
    fun rejectsBareUuid() {
        val uuid = "11111111-2222-3333-4444-555555555555"
        val result = VpnConnectionParser.parse(uuid)
        assertTrue(result.isFailure)
        assertEquals(VpnConnectionParser.REJECT_MESSAGE, result.exceptionOrNull()?.message)
        assertTrue(VpnConnectionParser.parseQr(uuid).isFailure)
    }

    @Test
    fun rejectsRandomText() {
        val result = VpnConnectionParser.parseQr("hello world")
        assertTrue(result.isFailure)
        assertEquals(VpnConnectionParser.REJECT_MESSAGE, result.exceptionOrNull()?.message)
    }

    @Test
    fun rejectsWifiQr() {
        assertTrue(
            VpnConnectionParser.parseQr("WIFI:S:Cafe;T:WPA;P:secret;;").isFailure,
        )
    }

    @Test
    fun rejectsVmess() {
        val result = VpnConnectionParser.parseQr("vmess://abcd")
        assertEquals(VpnConnectionParser.OTHER_PROTOCOL_MESSAGE, result.exceptionOrNull()?.message)
    }

    @Test
    fun rejectsGoogleHomePage() {
        assertTrue(VpnConnectionParser.parseQr("https://google.com").isFailure)
        assertTrue(VpnConnectionParser.parseQr("https://www.youtube.com/watch?v=123").isFailure)
    }

    @Test
    fun extractsVlessFromNoisyQr() {
        val raw = """
            Menu
            $vless
        """.trimIndent()
        val parsed = VpnConnectionParser.parseQr(raw).getOrThrow()
        assertTrue(parsed is VpnConnectionInput.VlessLink)
        assertEquals(vless, parsed.raw)
    }
}
