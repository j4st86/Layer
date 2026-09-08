package com.layer.core.routing

/**
 * Deterministic routing priority used by both unit tests and sing-box config generation.
 *
 * 1. Explicit user app DIRECT
 * 2. Explicit user app VPN
 * 3. User domain DIRECT
 * 4. User domain VPN
 * 5. Ad block, then automatic rule-set
 * 6. Local / private traffic
 * 7. Default DIRECT
 *
 * SMART apps (or apps without a user rule) skip 1–2 and continue with domain rules.
 * Manual user rules always beat automatic lists.
 */
object RoutingPriority {
    const val APP_DIRECT = 1
    const val APP_VPN = 2
    const val USER_DOMAIN_DIRECT = 3
    const val USER_DOMAIN_VPN = 4
    const val AUTOMATIC_RULE_SET = 5
    const val LOCAL_TRAFFIC = 6
    const val DEFAULT_DIRECT = 7
}
