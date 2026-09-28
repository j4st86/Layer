package com.layer.core.config

/**
 * Play Services owns FCM. A full-tunnel VPN reaches `mtalk.google.com` from
 * the server, so pushes keep arriving when the carrier path to Google does not.
 * Sending this process directly was the opposite: the tunnel stayed up while
 * the push session timed out on the phone network.
 *
 * These packages always use the proxy, ahead of user app rules and automatic
 * lists. Their names still resolve through the phone resolver. YouTube and the
 * Play Store stay on their own rules.
 */
object PushTunnelPackages {
    val packages: List<String> = listOf(
        "com.google.android.gms",
        "com.google.android.gsf",
    )
}
