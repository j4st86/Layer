package com.layer.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsGroupingTest {
    @Test
    fun splitsManualServersAndSubscriptionGroups() {
        val manualOne = SavedServer("m1", "Phone", VlessServerConfig(displayName = "Phone"))
        val manualTwo = SavedServer("m2", "Laptop", VlessServerConfig(displayName = "Laptop"))
        val subscription = SavedSubscription("s1", "sub.example.com", "https://sub.example.com/token")
        val fromSubOne = SavedServer("s1a", "Latvia", VlessServerConfig(displayName = "Latvia"), "s1")
        val fromSubTwo = SavedServer("s1b", "France", VlessServerConfig(displayName = "France"), "s1")
        val settings = LayerSettings(
            server = manualOne.config,
            activeServerId = "m1",
            servers = listOf(manualOne, manualTwo, fromSubOne, fromSubTwo),
            subscriptions = listOf(subscription),
        )
        assertEquals(listOf("Phone", "Laptop"), settings.manualServers().map { it.name })
        assertEquals(listOf("Latvia", "France"), settings.serversFor("s1").map { it.name })
        assertEquals("Phone", settings.activeConnectionName())
    }

    @Test
    fun noteOverridesVisibleNameWithoutChangingOriginal() {
        val server = SavedServer(
            id = "m1",
            name = "VLESS-TLS-Phone",
            config = VlessServerConfig(displayName = "VLESS-TLS-Phone"),
            note = "Домашний",
        )
        val subscription = SavedSubscription(
            id = "s1",
            name = "sub.example.com",
            url = "https://sub.example.com/token",
            note = "Рабочая",
        )
        assertEquals("Домашний", server.visibleName())
        assertEquals("Рабочая", subscription.visibleName())
        assertEquals("VLESS-TLS-Phone", server.copy(note = "  ").visibleName())
        assertEquals("sub.example.com", subscription.copy(note = "").visibleName())
    }
}
