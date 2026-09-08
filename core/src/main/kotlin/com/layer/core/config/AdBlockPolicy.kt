package com.layer.core.config

/**
 * AdGuard DNS filter from HostlistsRegistry: domain-level rules compiled for
 * DNS/VPN blocking, not cosmetic element hiding.
 *
 * Refresh cadence matches [RuleSetCatalog] (itdoginfo lists, every 3 days).
 * A copy of [BUNDLED_VERSION] ships in assets/adblock if GitHub Pages is gone
 * on first enable. Refresh with scripts/fetch-adblock.sh.
 */
object AdBlockPolicy {
    const val TAG = "rs-adguard"
    const val listUrl =
        "https://adguardteam.github.io/HostlistsRegistry/assets/filter_1.txt"
    const val fileName = "adguard-dns-filter.txt"
    const val ruleSetFileName = "adguard-dns-filter.json"
    const val ASSET_DIR = "adblock"
    const val BUNDLED_VERSION = "1.0.78.69"
    const val UPDATE_INTERVAL = RuleSetCatalog.UPDATE_INTERVAL
    const val FRESHNESS_MS = RuleSetCatalog.FRESHNESS_MS
}
