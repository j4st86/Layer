package com.layer.core.config

/**
 * Play Services owns FCM. If that process is SMART, `rs-google-play` sends
 * `mtalk.google.com` through VLESS. After a long lock the outbound is stale
 * while VpnService still says CONNECTED, so pushes sit until SCREEN_ON Wake.
 *
 * These packages always DIRECT, ahead of user app rules and automatic lists.
 * YouTube / Play Store stay on their own rules. Layer is a split-tunnel, not
 * a privacy VPN: FCM over the phone's network is the reliable path.
 */
object PushDirectPackages {
    val packages: List<String> = listOf(
        "com.google.android.gms",
        "com.google.android.gsf",
    )

    /**
     * Play Services also runs the Gemini eligibility check. While these were
     * excluded from TUN their DNS never reached sing-box, so the AI names came
     * back from the phone's resolver and Google answered the Russian address:
     * Gemini reported an unsupported region and vanished from the app list.
     * They stay DIRECT but inside TUN so [FixedAppRoutingPolicy] can point the
     * AI names at its own DoT. FCM still never touches VLESS.
     */
    val keepInTun: Set<String> = packages.toSet()
}
