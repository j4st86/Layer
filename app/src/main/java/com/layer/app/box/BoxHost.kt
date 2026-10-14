package com.layer.app.box

import android.net.NetworkCapabilities

/**
 * Callbacks the libbox adapter needs from the VPN host without taking a
 * dependency on LayerVpnService or LayerBoxService.
 */
interface BoxHost {
    fun dbg(message: String)
    fun recoverAfterHandoff(reason: String)

    /**
     * Underlying iface was published. A real wifi↔cell change rebuilds TUN
     * after a settle delay. Losing the network cancels that rebuild.
     */
    fun onUnderlyingIface(previous: String?, next: String)
    fun notifyAutoServerTransport(capabilities: NetworkCapabilities)
    fun onTunDump(dump: String)
}
