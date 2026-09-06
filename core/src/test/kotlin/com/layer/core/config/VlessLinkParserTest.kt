package com.layer.core.config

import com.layer.core.model.UUID_PLACEHOLDER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VlessLinkParserTest {
    private val sampleUuid = "11111111-2222-3333-4444-555555555555"

    @Test
    fun acceptsBareUuid() {
        val parsed = VlessLinkParser.parse(sampleUuid).getOrThrow()
        assertEquals(sampleUuid, parsed.uuid)
        assertNull(parsed.server)
    }

    @Test
    fun parsesFullVlessLink() {
        val link =
            "vless://$sampleUuid@vpn.example.com:443" +
                "?alpn=http%2F1.1&encryption=none&flow=xtls-rprx-vision" +
                "&fp=firefox&security=tls&sni=&type=tcp#Phone"
        val parsed = VlessLinkParser.parse(link).getOrThrow()
        assertEquals(sampleUuid, parsed.uuid)
        val server = parsed.server!!
        assertEquals("vpn.example.com", server.address)
        assertEquals(443, server.port)
        assertEquals("xtls-rprx-vision", server.flow)
        assertEquals("firefox", server.fingerprint)
        assertEquals("http/1.1", server.alpn)
        assertEquals("tcp", server.network)
        assertEquals("vpn.example.com", server.serverName)
        assertEquals("Phone", server.displayName)
        assertEquals("tls", server.security)
    }

    @Test
    fun usesDecodedFragmentAsDisplayName() {
        val link =
            "vless://$sampleUuid@vpn.example.com:443" +
                "?encryption=none&flow=xtls-rprx-vision&fp=firefox&security=tls&sni=&type=tcp" +
                "#%F0%9F%87%B1%F0%9F%87%BBVLESS-TLS-Phone"
        val server = VlessLinkParser.parse(link).getOrThrow().server!!
        assertTrue(server.displayName.contains("VLESS-TLS-Phone"))
        assertEquals("vpn.example.com", server.address)
    }

    @Test
    fun normalizesRealityPublicKeyToRawUrl() {
        val link =
            "vless://$sampleUuid@203.0.113.10:443" +
                "?encryption=none&flow=xtls-rprx-vision&fp=chrome" +
                "&pbk=abc+de/fg==&security=reality&sid=abcd1234efgh" +
                "&sni=www.example.com&type=tcp#Reality"
        val server = VlessLinkParser.parse(link).getOrThrow().server!!
        assertEquals("abc-de_fg", server.publicKey)
        assertEquals("reality", server.security)
        assertEquals("www.example.com", server.serverName)
        assertEquals("abcd1234efgh", server.shortId)
    }

    @Test
    fun realityWithoutFingerprintDefaultsToChrome() {
        val link =
            "vless://$sampleUuid@203.0.113.10:443" +
                "?encryption=none&flow=xtls-rprx-vision" +
                "&pbk=abc+de/fg==&security=reality&sid=abcd1234efgh" +
                "&sni=www.example.com&type=tcp#Reality"
        val server = VlessLinkParser.parse(link).getOrThrow().server!!
        assertEquals("chrome", server.fingerprint)
        assertEquals("reality", server.security)
    }

    @Test
    fun realityQueryKeysAreCaseInsensitive() {
        val link =
            "vless://$sampleUuid@203.0.113.10:443" +
                "?encryption=none&flow=xtls-rprx-vision&FP=chrome" +
                "&PBK=abc+de/fg==&SECURITY=reality&SID=abcd1234efgh" +
                "&SNI=www.example.com&type=tcp#Reality"
        val server = VlessLinkParser.parse(link).getOrThrow().server!!
        assertEquals("abc-de_fg", server.publicKey)
        assertEquals("reality", server.security)
        assertEquals("www.example.com", server.serverName)
        assertEquals("abcd1234efgh", server.shortId)
        assertEquals("chrome", server.fingerprint)
    }

    @Test
    fun rejectsPlaceholder() {
        assertTrue(VlessLinkParser.parse(UUID_PLACEHOLDER).isFailure)
    }

    @Test
    fun xhttpRealityDoesNotDefaultToVision() {
        val link =
            "vless://$sampleUuid@203.0.113.10:443?encryption=none" +
                "&extra=%7B%22mode%22%3A%22auto%22%2C%22xPaddingBytes%22%3A%22100-1000%22%7D" +
                "&fp=edge&host=www.example.com&mode=auto&path=%2F" +
                "&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                "&security=reality&sid=abcd1234&sni=www.example.com" +
                "&spx=%2F&type=xhttp&x_padding_bytes=100-1000#XHTTP"
        val server = VlessLinkParser.parse(link).getOrThrow().server!!
        assertEquals("xhttp", server.network)
        assertEquals("", server.flow)
        assertEquals("reality", server.security)
        assertEquals("www.example.com", server.serverName)
        assertEquals("www.example.com", server.httpHost)
        assertEquals("/", server.path)
        assertEquals("auto", server.transportMode)
        assertEquals("100-1000", server.xPaddingBytes)
        assertEquals("edge", server.fingerprint)
        assertEquals("XHTTP", server.displayName)
    }
}
