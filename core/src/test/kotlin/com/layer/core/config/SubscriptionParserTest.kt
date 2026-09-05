package com.layer.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SubscriptionParserTest {
    private val sampleUuid = "11111111-2222-3333-4444-555555555555"

    @Test
    fun parsesBase64VlessList() {
        val body = """
            vless://$sampleUuid@latvia.example:443?alpn=http%2F1.1&encryption=none&flow=xtls-rprx-vision&fp=firefox&security=tls&sni=&type=tcp#%F0%9F%87%B1%F0%9F%87%BBVLESS-TLS%40Latvia-2
            vless://$sampleUuid@203.0.113.10:443?encryption=none&flow=xtls-rprx-vision&fp=chrome&pbk=dGVzdC1wdWJsaWMta2V5LWZvci11bml0LXRlc3Rz&security=reality&sid=abcd1234efgh&sni=www.example.com&spx=%2F&type=tcp#VLESS-Reality%40France
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))
        val parsed = SubscriptionParser.parse(encoded).getOrThrow()
        assertEquals(2, parsed.size)
        assertEquals("latvia.example", parsed[0].server!!.address)
        assertTrue(parsed[0].server!!.displayName.contains("VLESS-TLS@Latvia-2"))
        assertEquals("reality", parsed[1].server!!.security)
        assertEquals("www.example.com", parsed[1].server!!.serverName)
        assertEquals("VLESS-Reality@France", parsed[1].server!!.displayName)
        assertTrue(parsed[1].server!!.publicKey.isNotBlank())
    }

    @Test
    fun usesHostAsSubscriptionName() {
        assertEquals(
            "sub.example.com",
            SubscriptionParser.subscriptionName("https://sub.example.com/subass/token"),
        )
    }
}
