package com.layer.app.box

import android.net.NetworkCapabilities

/**
 * Callbacks the libbox adapter needs from VpnService without taking a
 * dependency on LayerVpnService itself.
 */
interface BoxHost {
    fun dbg(message: String)
    fun recoverAfterHandoff(reason: String)
    fun notifyAutoServerTransport(capabilities: NetworkCapabilities)
    fun onTunDump(dump: String)
}
