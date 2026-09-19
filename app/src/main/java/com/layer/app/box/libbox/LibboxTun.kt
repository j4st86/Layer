package com.layer.app.box.libbox

import android.net.IpPrefix
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.layer.app.box.BoxHost
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import java.net.InetAddress

/**
 * Establishes the Android TUN from libbox TunOptions. minSdk is 34, so the
 * Q / Tiramisu VpnService APIs are used unconditionally.
 */
internal class LibboxTun(
    private val vpn: VpnService,
    private val host: BoxHost,
) {
    var fd: ParcelFileDescriptor? = null
        private set

    fun open(options: TunOptions): Int {
        if (VpnService.prepare(vpn) != null) error("android: missing vpn permission")
        val dump = StringBuilder()
        fun note(line: String) {
            dump.appendLine(line)
            host.dbg(line)
        }
        val builder = vpn.Builder()
            .setSession("Layer")
            .setMtu(options.mtu)
            .setMetered(false)
        note(
            "[TUN] mtu=${options.mtu} autoRoute=${options.autoRoute} " +
                "strict=${options.strictRoute} dnsMode=${options.dnsMode?.value}",
        )
        val inet4 = drainPrefixes(options.inet4Address)
        val inet6 = drainPrefixes(options.inet6Address)
        inet4.forEach { (address, prefix) ->
            builder.addAddress(address, prefix)
            note("[TUN] address $address/$prefix")
        }
        inet6.forEach { (address, prefix) ->
            builder.addAddress(address, prefix)
            note("[TUN] address6 $address/$prefix")
        }
        if (options.autoRoute) {
            val dnsServers = mutableListOf<String>()
            val dnsResult = runCatching {
                drainStrings(options.dnsServerAddress).also { dnsServers += it }
            }
            if (dnsResult.isFailure) {
                note("[TUN] dnsServerAddress error: ${dnsResult.exceptionOrNull()?.message}")
            }
            if (dnsServers.isEmpty()) {
                dnsServers += listOf("1.1.1.1", "8.8.8.8")
                note("[TUN] dns fallback=1.1.1.1,8.8.8.8 reason=libbox-empty")
            }
            dnsServers.forEach { server ->
                builder.addDnsServer(server)
                note("[TUN] dns $server")
            }
            val inet4Routes = drainPrefixes(options.inet4RouteAddress)
            if (inet4Routes.isNotEmpty()) {
                inet4Routes.forEach { (address, prefix) ->
                    builder.addRoute(address, prefix)
                    note("[TUN] route $address/$prefix")
                }
            } else {
                builder.addRoute("0.0.0.0", 0)
                note("[TUN] route 0.0.0.0/0 fallback reason=inet4RouteAddress-empty")
            }
            val inet6Routes = drainPrefixes(options.inet6RouteAddress)
            if (inet6Routes.isNotEmpty()) {
                inet6Routes.forEach { (address, prefix) ->
                    builder.addRoute(address, prefix)
                    note("[TUN] route6 $address/$prefix")
                }
            } else if (inet6.isNotEmpty()) {
                builder.addRoute("::", 0)
                note("[TUN] route ::/0")
            }
            drainPrefixes(options.inet4RouteExcludeAddress).forEach { (address, prefix) ->
                builder.excludeRoute(IpPrefix(InetAddress.getByName(address), prefix))
                note("[TUN] exclude $address/$prefix")
            }
            drainPrefixes(options.inet6RouteExcludeAddress).forEach { (address, prefix) ->
                builder.excludeRoute(IpPrefix(InetAddress.getByName(address), prefix))
                note("[TUN] exclude6 $address/$prefix")
            }
            drainStrings(options.includePackage).forEach { pkg ->
                runCatching { builder.addAllowedApplication(pkg) }
                    .onSuccess { note("[TUN] allow $pkg") }
                    .onFailure { note("[TUN] allow $pkg failed: ${it.message}") }
            }
            drainStrings(options.excludePackage).forEach { pkg ->
                runCatching { builder.addDisallowedApplication(pkg) }
                    .onSuccess { note("[TUN] disallow $pkg") }
                    .onFailure { note("[TUN] disallow $pkg failed: ${it.message}") }
            }
        }
        runCatching { builder.addDisallowedApplication(vpn.packageName) }
            .onSuccess { note("[TUN] disallow self ${vpn.packageName}") }
            .onFailure { note("[TUN] disallow self failed: ${it.message}") }
        // Close the old PFD before establish(). The next establish() already
        // invalidates it; reading previous.fd afterwards throws "Already closed".
        val previous = fd
        fd = null
        if (previous != null) {
            runCatching { previous.close() }
                .onSuccess { note("[TUN] closed previous") }
                .onFailure { note("[TUN] previous close: ${it.message}") }
        }
        val pfd = builder.establish() ?: error("android: the application is not prepared or is revoked")
        fd = pfd
        note("[TUN] establish ok fd=${pfd.fd}")
        host.onTunDump(dump.toString().trim())
        return pfd.fd
    }

    fun close() {
        runCatching { fd?.close() }
        fd = null
    }

    private fun drainPrefixes(iterator: RoutePrefixIterator): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        while (iterator.hasNext()) {
            val prefix = iterator.next() ?: continue
            out += prefix.address() to prefix.prefix()
        }
        return out
    }

    private fun drainStrings(iterator: StringIterator): List<String> {
        val out = mutableListOf<String>()
        while (iterator.hasNext()) {
            val value = iterator.next()
            if (!value.isNullOrBlank()) out += value
        }
        return out
    }
}
