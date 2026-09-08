package com.layer.core.config

/**
 * AdGuard DNS filter from HostlistsRegistry: domain-level rules compiled for
 * DNS/VPN blocking, not cosmetic element hiding.
 *
 * AdGuard marks it `expires` 4 days; the compiled file is rebuilt most days.
 * Layer lets the user pick 1/2/3/7. A copy of [BUNDLED_VERSION] ships in
 * assets/adblock if GitHub Pages is gone on first enable. Refresh with
 * scripts/fetch-adblock.sh. Routing the list into TUN is follow-up.
 */
object AdBlockPolicy {
    val intervalDays: List<Int> = listOf(1, 2, 3, 7)
    const val defaultIntervalDays = 7
    const val listUrl =
        "https://adguardteam.github.io/HostlistsRegistry/assets/filter_1.txt"
    const val fileName = "adguard-dns-filter.txt"
    const val ASSET_DIR = "adblock"
    const val BUNDLED_VERSION = "1.0.78.69"

    fun clampIntervalDays(days: Int): Int =
        intervalDays.firstOrNull { it == days } ?: defaultIntervalDays

    fun freshnessMs(days: Int): Long =
        clampIntervalDays(days) * 24L * 60L * 60L * 1000L
}
