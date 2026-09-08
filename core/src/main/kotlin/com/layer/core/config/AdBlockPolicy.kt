package com.layer.core.config

/**
 * Optional DNS hostlist for ads and trackers (ABP-style `||domain^`).
 *
 * Upstream is the GPL-3.0 HostlistsRegistry `filter_1` feed; see THIRD_PARTY.md.
 * Layer is not affiliated with the list authors. Refresh cadence matches
 * [RuleSetCatalog] (3 days). Remote refresh is Wi‑Fi/Ethernet only so
 * cellular is not charged ~4 MB; itdog lists still download on mobile.
 * A copy of [BUNDLED_VERSION] ships in assets/adblock if the feed is gone
 * on first enable. Refresh with scripts/fetch-adblock.sh.
 */
object AdBlockPolicy {
    const val TAG = "rs-ads"
    const val listUrl =
        "https://adguardteam.github.io/HostlistsRegistry/assets/filter_1.txt"
    const val fileName = "dns-ad-filter.txt"
    const val ruleSetFileName = "dns-ad-filter.json"
    const val ASSET_DIR = "adblock"
    const val BUNDLED_VERSION = "1.0.78.69"
    const val UPDATE_INTERVAL = RuleSetCatalog.UPDATE_INTERVAL
    const val FRESHNESS_MS = RuleSetCatalog.FRESHNESS_MS
}
