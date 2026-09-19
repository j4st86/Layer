package com.layer.app.box

import android.net.NetworkCapabilities

/**
 * Callbacks the libbox adapter needs from the VPN host without taking a
 * dependency on LayerVpnService or LayerBoxService.
 */
interface BoxHost {
    fun dbg(message: String)
    fun recoverAfterHandoff(reason: String)
    fun notifyAutoServerTransport(capabilities: NetworkCapabilities)
    fun onTunDump(dump: String)
}
