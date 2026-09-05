package com.layer.core.config

import com.layer.core.i18n.copy

data class AutomaticRuleSet(
    val tag: String,
    val title: String,
    val url: String,
)

/**
 * Remote SRS lists from itdoginfo/allow-domains.
 * Their GitHub releases are weekly, so Layer refreshes every 3 days.
 */
object RuleSetCatalog {
    const val UPDATE_INTERVAL = "3d"
    const val FRESHNESS_MS = 3L * 24 * 60 * 60 * 1000
    private const val BASE =
        "https://github.com/itdoginfo/allow-domains/releases/latest/download"

    val vpnLists: List<AutomaticRuleSet>
        get() = listOf(
            AutomaticRuleSet("rs-youtube", "YouTube", "$BASE/youtube.srs"),
            AutomaticRuleSet("rs-meta", "Instagram / Facebook", "$BASE/meta.srs"),
            AutomaticRuleSet("rs-twitter", "X / Twitter", "$BASE/twitter.srs"),
            AutomaticRuleSet("rs-telegram", "Telegram", "$BASE/telegram.srs"),
            AutomaticRuleSet("rs-discord", "Discord", "$BASE/discord.srs"),
            AutomaticRuleSet("rs-google-ai", "Google AI", "$BASE/google_ai.srs"),
            AutomaticRuleSet("rs-tiktok", "TikTok", "$BASE/tiktok.srs"),
            AutomaticRuleSet("rs-news", copy("Foreign media", "Зарубежные СМИ"), "$BASE/news.srs"),
            AutomaticRuleSet("rs-hdrezka", "HDRezka", "$BASE/hdrezka.srs"),
            AutomaticRuleSet("rs-google-play", "Google Play", "$BASE/google_play.srs"),
            AutomaticRuleSet("rs-cloudflare", "Cloudflare", "$BASE/cloudflare.srs"),
            AutomaticRuleSet("rs-geoblock", copy("Geoblock", "Геоблок"), "$BASE/geoblock.srs"),
        )

    val tags: List<String> get() = vpnLists.map { it.tag }
}
