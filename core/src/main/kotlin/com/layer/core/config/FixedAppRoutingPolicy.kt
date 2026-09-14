package com.layer.core.config

/**
 * Routing that is part of Layer itself and must not be user-overridable.
 *
 * Gemini stays inside TUN so sing-box can select its DNS by Android package,
 * but its application traffic always uses the DIRECT outbound.
 */
object FixedAppRoutingPolicy {
    const val GEMINI_PACKAGE = "com.google.android.apps.bard"
    const val GEMINI_DNS_HOST = "xbox-dns.ru"
    const val GEMINI_DNS_TAG = "dns-gemini"

    val hiddenPackages: Set<String> = setOf(GEMINI_PACKAGE)

    fun isUserConfigurable(packageName: String): Boolean =
        packageName.trim() !in hiddenPackages
}
