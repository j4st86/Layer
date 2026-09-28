package com.layer.core.config

/**
 * Phone DNS sometimes still answers with a fake-ip left by another VPN.
 * 198.18.0.0/15 is the usual range. Accepting it makes sing-box cache a
 * blackhole for the TTL, and the route reject drops the connection before
 * the proxy rule. Loopback and the class-E range are the same kind of answer.
 */
object RoutableDnsAnswers {
    fun usable(addresses: List<String>): List<String> = addresses.filterNot(::isUnusable)

    fun isUnusable(address: String): Boolean {
        val parts = address.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val first = octets[0]
        val second = octets[1]
        if (first == 0 || first == 127 || first >= 240) return true
        return first == 198 && (second == 18 || second == 19)
    }
}
