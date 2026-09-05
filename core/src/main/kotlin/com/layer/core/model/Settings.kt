package com.layer.core.model

import kotlinx.serialization.Serializable

const val UUID_PLACEHOLDER = "YOUR_VLESS_UUID"
const val DEFAULT_VLESS_PORT = 443
const val DEFAULT_VLESS_FLOW = "xtls-rprx-vision"
const val DEFAULT_TLS_FINGERPRINT = "firefox"
const val DEFAULT_TLS_ALPN = "http/1.1"

@Serializable
data class VlessServerConfig(
    val address: String = "",
    val port: Int = DEFAULT_VLESS_PORT,
    val flow: String = DEFAULT_VLESS_FLOW,
    val network: String = "tcp",
    val serverName: String = address,
    val fingerprint: String = DEFAULT_TLS_FINGERPRINT,
    val alpn: String = DEFAULT_TLS_ALPN,
    val displayName: String = "",
    val security: String = "tls",
    val publicKey: String = "",
    val shortId: String = "",
    val spiderX: String = "",
    val path: String = "",
    val httpHost: String = "",
    val transportMode: String = "",
    val xPaddingBytes: String = "",
) {
    val isReality: Boolean get() = security.equals("reality", ignoreCase = true) && publicKey.isNotBlank()

    fun visibleName(): String = displayName.ifBlank { address }
}

@Serializable
data class SavedServer(
    val id: String,
    val name: String,
    val config: VlessServerConfig,
    val subscriptionId: String? = null,
    val note: String = "",
) {
    fun visibleName(): String = note.trim().ifBlank { name.ifBlank { config.visibleName() } }
}

@Serializable
data class SavedSubscription(
    val id: String,
    val name: String,
    val url: String,
    val lastUpdatedEpochMs: Long = 0,
    val note: String = "",
) {
    fun visibleName(): String = note.trim().ifBlank { name }
}

data class LayerSettings(
    val server: VlessServerConfig = VlessServerConfig(),
    val activeServerId: String? = null,
    val servers: List<SavedServer> = emptyList(),
    val subscriptions: List<SavedSubscription> = emptyList(),
    val automaticRuleSetEnabled: Boolean = true,
    val ipv6Enabled: Boolean = false,
    val recommendedAppsPromptDone: Boolean = false,
    val autoSelectServerEnabled: Boolean = false,
    val autoSelectIntervalMinutes: Int = 10,
) {
    fun manualServers(): List<SavedServer> = servers.filter { it.subscriptionId == null }

    fun serversFor(subscriptionId: String): List<SavedServer> =
        servers.filter { it.subscriptionId == subscriptionId }

    fun activeServer(): SavedServer? =
        servers.find { it.id == activeServerId } ?: servers.firstOrNull()

    fun activeConnectionName(): String {
        val named = activeServer()?.visibleName()
        return named?.ifBlank { null } ?: server.visibleName()
    }

    fun select(id: String): LayerSettings {
        val chosen = servers.find { it.id == id } ?: return this
        return copy(activeServerId = chosen.id, server = chosen.config)
    }

    fun replaceServer(updated: SavedServer): LayerSettings {
        val list = servers.map { if (it.id == updated.id) updated else it }
        val active = if (activeServerId == updated.id) updated.config else server
        return copy(servers = list, server = active)
    }

    fun replaceSubscription(updated: SavedSubscription): LayerSettings {
        return copy(subscriptions = subscriptions.map { if (it.id == updated.id) updated else it })
    }

    fun withoutServer(id: String): LayerSettings {
        val remaining = servers.filterNot { it.id == id }
        val next = remaining.find { it.id == activeServerId } ?: remaining.firstOrNull()
        return copy(
            servers = remaining,
            activeServerId = next?.id,
            server = next?.config ?: VlessServerConfig(),
        )
    }

    fun withoutSubscription(id: String): LayerSettings {
        val remaining = servers.filterNot { it.subscriptionId == id }
        val next = remaining.find { it.id == activeServerId } ?: remaining.firstOrNull()
        return copy(
            subscriptions = subscriptions.filterNot { it.id == id },
            servers = remaining,
            activeServerId = next?.id,
            server = next?.config ?: VlessServerConfig(),
        )
    }
}
