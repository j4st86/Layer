package com.layer.core.config

import com.layer.core.model.VlessServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionIdentityTest {
    private val uuid = "11111111-2222-3333-4444-555555555555"
    private val config = VlessServerConfig(
        address = "vpn.example.com",
        port = 443,
        security = "reality",
        publicKey = "abc-de_fg",
        shortId = "abcd1234",
    )

    @Test
    fun subscriptionKeyIgnoresWhitespaceAndTrailingSlash() {
        assertEquals(
            "https://sub.example.com/token",
            ConnectionIdentity.subscriptionKey("  https://sub.example.com/token/  "),
        )
    }

    @Test
    fun matchesSameVlessEvenIfRemarkDiffers() {
        val incoming = ParsedVlessLink(
            uuid = uuid,
            server = config.copy(displayName = "Another name"),
        )
        assertTrue(ConnectionIdentity.matches(uuid, config, incoming))
    }

    @Test
    fun matchesBareUuidAgainstExistingServer() {
        val incoming = ParsedVlessLink(uuid = uuid.uppercase())
        assertTrue(ConnectionIdentity.matches(uuid, config, incoming))
    }

    @Test
    fun doesNotMatchDifferentHost() {
        val incoming = ParsedVlessLink(
            uuid = uuid,
            server = config.copy(address = "other.example.com"),
        )
        assertFalse(ConnectionIdentity.matches(uuid, config, incoming))
    }

    @Test
    fun doesNotMatchDifferentUuid() {
        val incoming = ParsedVlessLink(
            uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
            server = config,
        )
        assertFalse(ConnectionIdentity.matches(uuid, config, incoming))
    }

    @Test
    fun doesNotMatchDifferentRealityShortId() {
        val incoming = ParsedVlessLink(
            uuid = uuid,
            server = config.copy(shortId = "ffffffff"),
        )
        assertFalse(ConnectionIdentity.matches(uuid, config, incoming))
    }
}
