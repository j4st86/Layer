package com.layer.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress

data class HostResolveResult(
    val ip: String?,
    val error: String? = null,
)

object UnderlyingDns {
    fun pickUnderlyingNetwork(connectivity: ConnectivityManager): Network? {
        val candidates = connectivity.allNetworks.filter { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@filter false
            isUnderlying(caps)
        }
        return candidates.maxByOrNull { rank(connectivity.getNetworkCapabilities(it)) }
    }

    fun shouldUseAsUnderlying(
        connectivity: ConnectivityManager,
        current: Network?,
        candidate: Network,
    ): Boolean {
        if (current == null || current == candidate) return true
        val currentRank = rank(connectivity.getNetworkCapabilities(current))
        val candidateRank = rank(connectivity.getNetworkCapabilities(candidate))
        return candidateRank >= currentRank
    }

    fun isUnderlying(caps: NetworkCapabilities): Boolean {
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun rank(caps: NetworkCapabilities?): Int {
        if (caps == null || !isUnderlying(caps)) return 0
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 2
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
            else -> 0
        }
    }

    fun resolve(context: Context, host: String): HostResolveResult {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return HostResolveResult(null, "empty-name")
        if (isIpv4(trimmed)) return HostResolveResult(trimmed)

        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val errors = mutableListOf<String>()
        val networks = listOfNotNull(
            pickUnderlyingNetwork(connectivity),
            connectivity.activeNetwork,
        ).distinct()

        for (network in networks) {
            val addresses = runCatching { network.getAllByName(trimmed) }
                .onFailure { errors += it.javaClass.simpleName }
                .getOrNull()
            pickIp(addresses)?.let { return HostResolveResult(it) }
        }

        val addresses = runCatching { InetAddress.getAllByName(trimmed) }
            .onFailure { errors += it.message ?: it.javaClass.simpleName }
            .getOrNull()
        pickIp(addresses)?.let { return HostResolveResult(it) }

        return HostResolveResult(
            ip = null,
            error = errors.joinToString("; ").ifBlank { "empty-dns-response" },
        )
    }

    private fun pickIp(addresses: Array<out InetAddress>?): String? {
        if (addresses.isNullOrEmpty()) return null
        return addresses.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.firstOrNull()
            ?: addresses.mapNotNull { it.hostAddress }.firstOrNull()
    }

    private fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
    }
}
