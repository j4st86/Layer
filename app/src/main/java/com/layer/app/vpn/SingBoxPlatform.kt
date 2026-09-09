package com.layer.app.vpn

import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Process
import android.system.OsConstants
import com.layer.core.diagnostics.LogSanitizer
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface

class SingBoxPlatform(private val service: LayerVpnService) : PlatformInterface {
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var protectCount = 0
    private var lookupCount = 0

    @Volatile
    var underlyingNetwork: Network? = null
        private set

    private var lastValidated = false
    private var lastIfaceLog = ""

    private val connectivity: ConnectivityManager
        get() = service.getSystemService(ConnectivityManager::class.java)

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        service.protect(fd)
        protectCount += 1
        if (protectCount <= 25 || protectCount % 50 == 0) {
            service.dbg("[NET] protect fd=$fd count=$protectCount")
        }
    }

    override fun openTun(options: TunOptions): Int = service.openTun(options)

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            error("android: connection owner requires API 29")
        }
        val uid = connectivity.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort),
        )
        if (uid == Process.INVALID_UID) error("android: connection owner not found")
        val packages = service.packageManager.getPackagesForUid(uid)
        return ConnectionOwner().apply {
            userId = uid
            userName = packages?.firstOrNull().orEmpty()
            setAndroidPackageNames(StringArray(packages?.toList().orEmpty()))
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val cm = connectivity
        underlyingNetwork = UnderlyingDns.pickUnderlyingNetwork(cm)
        val first = underlyingNetwork
        if (first != null) {
            val name = cm.getLinkProperties(first)?.interfaceName.orEmpty()
            service.dbg("[NET] underlying=$name")
            notifyInterfaceUpdate(cm, first, listener)
        } else {
            service.dbg("[NET] underlying network not found")
        }
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!isUnderlying(cm, network)) return
                if (!UnderlyingDns.shouldUseAsUnderlying(cm, underlyingNetwork, network)) return
                underlyingNetwork = network
                notifyInterfaceUpdate(cm, network, listener)
                cm.getNetworkCapabilities(network)?.let { service.notifyAutoServerTransport(it) }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (!isUnderlying(cm, network)) return
                if (!UnderlyingDns.shouldUseAsUnderlying(cm, underlyingNetwork, network)) return
                underlyingNetwork = network
                notifyInterfaceUpdate(cm, network, listener)
                val validated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (validated && !lastValidated) {
                    service.recoverAfterHandoff("network-validated")
                }
                lastValidated = validated
                service.notifyAutoServerTransport(networkCapabilities)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (!isUnderlying(cm, network)) return
                if (!UnderlyingDns.shouldUseAsUnderlying(cm, underlyingNetwork, network)) return
                underlyingNetwork = network
                val ifName = linkProperties.interfaceName.orEmpty()
                listener.updateDefaultInterface(ifName, interfaceIndex(ifName), false, false)
            }

            override fun onLost(network: Network) {
                if (underlyingNetwork == network) {
                    lastValidated = false
                    underlyingNetwork = UnderlyingDns.pickUnderlyingNetwork(cm)
                    val fallback = underlyingNetwork
                    if (fallback != null) {
                        notifyInterfaceUpdate(cm, fallback, listener)
                        service.recoverAfterHandoff("network-lost")
                        cm.getNetworkCapabilities(fallback)?.let {
                            service.notifyAutoServerTransport(it)
                        }
                    } else {
                        listener.updateDefaultInterface("", -1, false, false)
                    }
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(request, networkCallback!!)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        networkCallback?.let {
            runCatching { connectivity.unregisterNetworkCallback(it) }
            networkCallback = null
        }
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val cm = connectivity
        val networks = cm.allNetworks
        val systemInterfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val interfaces = mutableListOf<LibboxNetworkInterface>()
        for (network in networks) {
            val lp = cm.getLinkProperties(network) ?: continue
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                continue
            }
            val sysIf = systemInterfaces.find { it.name == lp.interfaceName } ?: continue
            val boxIf = LibboxNetworkInterface()
            boxIf.name = lp.interfaceName
            boxIf.index = sysIf.index
            runCatching { boxIf.mtu = sysIf.mtu }
            boxIf.type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                else -> Libbox.InterfaceTypeOther
            }
            boxIf.dnsServer = StringArray(
                lp.dnsServers.mapNotNull { it.hostAddress }
                    .filter { address ->
                        address.isNotBlank() &&
                            address != "::1" &&
                            !address.startsWith("127.")
                    },
            )
            boxIf.gateway = StringArray(
                lp.routes
                    .filter { it.destination.prefixLength == 0 }
                    .mapNotNull { it.gateway?.hostAddress }
                    .filter { it.isNotBlank() },
            )
            boxIf.addresses = StringArray(
                sysIf.interfaceAddresses.map { ia ->
                    val host = if (ia.address is Inet6Address) {
                        Inet6Address.getByAddress(ia.address.address).hostAddress
                    } else {
                        ia.address.hostAddress
                    }
                    "$host/${ia.networkPrefixLength}"
                },
            )
            var flags = 0
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            }
            if (sysIf.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
            if (sysIf.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
            if (sysIf.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
            boxIf.flags = flags
            boxIf.metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            interfaces.add(boxIf)
        }
        val snapshot = interfaces.joinToString { "${it.name}#${it.index}/${it.type}/${it.metered}" }
        if (snapshot != lastIfaceLog) {
            lastIfaceLog = snapshot
            interfaces.forEach { boxIf ->
                service.dbg("[NET] iface ${boxIf.name} idx=${boxIf.index} type=${boxIf.type} metered=${boxIf.metered}")
            }
        }
        return object : NetworkInterfaceIterator {
            private val iterator = interfaces.iterator()
            override fun hasNext(): Boolean = iterator.hasNext()
            override fun next(): LibboxNetworkInterface = iterator.next()
        }
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun readWIFIState(): WIFIState? = null

    override fun clearDNSCache() = Unit

    override fun localDNSTransport(): LocalDNSTransport {
        return object : LocalDNSTransport {
            override fun raw(): Boolean = false

            override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
                try {
                    val net = underlyingNetwork ?: UnderlyingDns.pickUnderlyingNetwork(connectivity)
                    val addresses = if (net != null) {
                        net.getAllByName(domain)
                    } else {
                        InetAddress.getAllByName(domain)
                    }
                    val ips = addresses.mapNotNull { it.hostAddress }.filter { it.isNotBlank() }
                    if (ips.isEmpty()) {
                        service.dbg("[DNS] local $domain empty")
                        ctx.errorCode(3)
                        return
                    }
                    lookupCount += 1
                    if (lookupCount <= 30 || lookupCount % 40 == 0) {
                        service.dbg("[DNS] local $domain → ${ips.joinToString()}")
                    }
                    ctx.success(ips.joinToString("\n"))
                } catch (error: Exception) {
                    service.dbg("[DNS] local $domain error: ${error.message}")
                    ctx.errorCode(3)
                }
            }

            override fun exchange(ctx: ExchangeContext, message: ByteArray) {
                ctx.errorCode(0)
            }
        }
    }

    override fun sendNotification(notification: io.nekohasekai.libbox.Notification) = Unit

    override fun cancelNotification(identifier: String, typeID: Int) = Unit

    override fun startNeighborMonitor(listener: NeighborUpdateListener) = Unit

    override fun closeNeighborMonitor(listener: NeighborUpdateListener) = Unit

    override fun registerMyInterface(name: String) = Unit

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() {
        error("shell is not supported")
    }

    override fun openShellSession(
        user: PlatformUser,
        command: String,
        environ: StringIterator,
        term: String,
        rows: Int,
        cols: Int,
    ): ShellSession {
        error("shell is not supported")
    }

    override fun lookupUser(username: String): PlatformUser {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val info = runCatching {
                service.packageManager.getApplicationInfo(
                    username,
                    PackageManager.ApplicationInfoFlags.of(0),
                )
            }.getOrNull()
            if (info != null) {
                return PlatformUser().apply {
                    this.username = username
                    uid = info.uid
                    gid = info.uid
                    homeDir = info.dataDir.orEmpty()
                }
            }
        }
        error("user not found")
    }

    override fun lookupSFTPServer(): String {
        error("sftp is not supported")
    }

    override fun readSystemSSHHostKey(): String {
        error("ssh host key is not supported")
    }

    override fun tailscaleHostname(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions): BridgeSession {
        error("bridge is not supported")
    }

    private fun isUnderlying(cm: ConnectivityManager, network: Network): Boolean {
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return UnderlyingDns.isUnderlying(caps)
    }

    private fun notifyInterfaceUpdate(
        cm: ConnectivityManager,
        network: Network,
        listener: InterfaceUpdateListener,
    ) {
        val lp = cm.getLinkProperties(network) ?: return
        val ifName = lp.interfaceName ?: return
        listener.updateDefaultInterface(ifName, interfaceIndex(ifName), false, false)
    }

    private fun interfaceIndex(name: String): Int {
        if (name.isEmpty()) return 0
        return runCatching { java.net.NetworkInterface.getByName(name)?.index ?: 0 }.getOrDefault(0)
    }

    class StringArray(items: List<String>) : StringIterator {
        private val snapshot = items
        private val iterator = snapshot.iterator()
        override fun len(): Int = snapshot.size
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): String = iterator.next()
    }
}
