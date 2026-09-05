package com.layer.core.model

data class RoutingDecision(
    val outbound: RoutingOutbound,
    val reason: RoutingReason,
) {
    val usesVpn: Boolean get() = outbound == RoutingOutbound.VPN
}
