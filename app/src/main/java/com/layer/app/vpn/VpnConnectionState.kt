package com.layer.app.vpn

enum class VpnConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR,
}

data class VpnUiStatus(
    val state: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val message: String = "",
    val errorTitle: String? = null,
    val errorDetails: String? = null,
)
