package com.layer.core.config

/**
 * Optional DNS hostlist for ads and trackers (ABP-style `||domain^`).
 *
 * Upstream is Hagezi Multi PRO, GPL-3.0; see THIRD_PARTY.md.
 * Layer is not affiliated with the list authors. Refresh cadence matches
 * [RuleSetCatalog] (7 days). Remote refresh is Wi‑Fi/Ethernet only so
 * cellular is not charged ~5 MB; itdog lists still download on mobile.
 * A copy of [BUNDLED_VERSION] ships in assets/adblock if the feed is gone
 * on first enable. Refresh with scripts/fetch-adblock.sh.
 */
object AdBlockPolicy {
    const val TAG = "rs-ads"
    const val listUrl =
        "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/pro.txt"
    const val fileName = "hagezi-pro.txt"
    const val ruleSetFileName = "hagezi-pro.json"
    const val ASSET_DIR = "adblock"
    const val BUNDLED_VERSION = "2026.0921.0824.43"
    const val UPDATE_INTERVAL = RuleSetCatalog.UPDATE_INTERVAL
    const val FRESHNESS_MS = RuleSetCatalog.FRESHNESS_MS
}
