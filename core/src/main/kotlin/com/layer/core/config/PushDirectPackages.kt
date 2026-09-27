package com.layer.core.config

/**
 * Play Services owns FCM. If that process is SMART, `rs-google-play` sends
 * `mtalk.google.com` through VLESS. After a long lock the outbound is stale
 * while VpnService still says CONNECTED, so pushes sit until SCREEN_ON Wake.
 *
 * These packages always DIRECT, ahead of user app rules and automatic lists,
 * and their DNS uses the phone resolver. YouTube / Play Store stay on their
 * own rules. Layer is a split-tunnel, not a privacy VPN: FCM over the phone's
 * network is the reliable path.
 */
object PushDirectPackages {
    val packages: List<String> = listOf(
        "com.google.android.gms",
        "com.google.android.gsf",
    )
}
