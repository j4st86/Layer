package com.layer.app.vpn

/**
 * Internal VPN lifecycle. UI still sees [VpnConnectionState]; this enum exists
 * so start/stop/reload cannot race on a boolean `stopping` flag. Stop is a
 * phase, not a latch that start later clears.
 */
internal enum class VpnPhase {
    Stopped,
    Starting,
    Started,
    Reloading,
    Stopping,
    Failed,
    ;

    fun isSessionLive(): Boolean = this == Starting || this == Started || this == Reloading

    fun toUi(): VpnConnectionState = when (this) {
        Stopped, Stopping -> VpnConnectionState.DISCONNECTED
        Starting -> VpnConnectionState.CONNECTING
        Started -> VpnConnectionState.CONNECTED
        Reloading -> VpnConnectionState.RECONNECTING
        Failed -> VpnConnectionState.ERROR
    }
}
