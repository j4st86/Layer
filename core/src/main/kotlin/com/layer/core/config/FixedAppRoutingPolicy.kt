package com.layer.core.config

/**
 * Routing that is part of Layer itself and must not be user-overridable.
 *
 * Gemini stays inside TUN so sing-box can select its DNS by Android package,
 * but its application traffic always uses the DIRECT outbound.
 *
 * [AI_DOMAIN_SUFFIXES] replaces the itdoginfo `google_ai` list: those names
 * are resolved by [GEMINI_DNS_HOST] over DoT, which answers with a relay that
 * unblocks the region, so the traffic must not also climb into VLESS. The list
 * is fixed in the app because the relay, not a remote rule set, decides what
 * is reachable.
 */
object FixedAppRoutingPolicy {
    const val GEMINI_PACKAGE = "com.google.android.apps.bard"
    const val GEMINI_DNS_HOST = "xbox-dns.ru"
    const val GEMINI_DNS_TAG = "dns-gemini"

    val hiddenPackages: Set<String> = setOf(GEMINI_PACKAGE)

    /**
     * Suffixes, so `gemini.google` also covers `gemini.google.com`. Kept in
     * sync with itdoginfo/allow-domains `Services/google_ai.lst`.
     */
    val AI_DOMAIN_SUFFIXES: List<String> = listOf(
        "aida.googleapis.com",
        "ai.google.dev",
        "aisandbox-pa.googleapis.com",
        "aistudio.google.com",
        "antigravity.google",
        "antigravity.googleapis.com",
        "antigravity-pa.googleapis.com",
        "antigravity-unleash.goog",
        "bard.google.com",
        "clients6.google.com",
        "deepmind.com",
        "deepmind.google",
        "firebaseinstallations.googleapis.com",
        "gemini.google",
        "geller-pa.googleapis.com",
        "generativeai.google",
        "generativelanguage.googleapis.com",
        "jules.google",
        "labs.google",
        "makersuite.google.com",
        "notebooklm.google",
        "proactivebackend-pa.googleapis.com",
        "robinfrontend-pa.googleapis.com",
        "speechs3proto2-pa.googleapis.com",
        "stitch.withgoogle.com",
    )

    fun isUserConfigurable(packageName: String): Boolean =
        packageName.trim() !in hiddenPackages
}
