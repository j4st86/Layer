package com.layer.core.config

/**
 * The VLESS server field is dialed as an IP. SNI stays the hostname.
 *
 * Phone DNS is empty for several seconds after boot and after Wi-Fi drops.
 * A hostname in the server field makes every new proxy dial wait on that
 * lookup, so Telegram's 5222 connect dies during wifi→cell even though the
 * tunnel itself is up. A later Wake does not help: the name is resolved again.
 *
 * When the lookup misses, retry for [retryCount] × [retryDelayMs] (8s). A
 * boot log showed the name missing at start and answering 7s later. If it is
 * still missing, use the last IPv4 that worked for this host. Handoff stays
 * a Wake and does not reload TUN to refresh the address.
 */
object ServerDialAddress {
    const val retryCount = 4
    const val retryDelayMs = 2_000L

    fun select(freshIp: String?, rememberedIp: String?): String? {
        freshIp?.takeIf { it.isNotBlank() }?.let { return it }
        return rememberable(rememberedIp)
    }

    fun rememberable(ip: String?): String? {
        val trimmed = ip?.trim().orEmpty()
        if (!isIpv4(trimmed)) return null
        return trimmed
    }

    private fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
    }
}
