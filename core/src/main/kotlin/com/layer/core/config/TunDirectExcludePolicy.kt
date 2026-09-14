package com.layer.core.config

/**
 * Route DIRECT still sends packets through TUN → gVisor → UID match.
 * TUN `exclude_package` (VpnService addDisallowedApplication) keeps those
 * UIDs off the VPN fd entirely.
 *
 * Android forbids mixing include_package and exclude_package. Layer is
 * exclude-only. Lockdown (“Block connections without VPN”) blackholes
 * excluded apps, so the Android side must pass [excludeDirectFromTun]=false
 * and skip addDisallowedApplication when lockdown is on.
 *
 * Exclusion and app DIRECT are per UID, not per package. sing-box sees every
 * package on a UID. If a SMART or VPN app shares that UID, putting the
 * DIRECT sibling in the app DIRECT rule would send the whole UID DIRECT.
 * Mixed UIDs stay in TUN and are omitted from app DIRECT so domain / VPN
 * rules still apply. An unresolved UID lookup is treated the same way.
 * GMS/GSF keep their own DIRECT rule.
 */
object TunDirectExcludePolicy {
    const val SKIP_MIXED_VPN = "mixed-vpn"
    const val SKIP_MIXED_SMART = "mixed-smart"
    const val SKIP_UNRESOLVED = "unresolved"

    data class Skip(
        val packageName: String,
        val reason: String,
        val uidPackages: List<String>,
    )

    data class Decision(
        val packages: List<String>,
        val routeDirectApps: List<String> = emptyList(),
        val skipped: List<Skip> = emptyList(),
    )

    fun packages(
        excludeDirectFromTun: Boolean,
        directApps: List<String>,
        vpnApps: List<String> = emptyList(),
        packagesSharingUid: (String) -> Collection<String>? = { listOf(it) },
    ): List<String> = decide(
        excludeDirectFromTun = excludeDirectFromTun,
        directApps = directApps,
        vpnApps = vpnApps,
        packagesSharingUid = packagesSharingUid,
    ).packages

    fun decide(
        excludeDirectFromTun: Boolean,
        directApps: List<String>,
        vpnApps: List<String> = emptyList(),
        packagesSharingUid: (String) -> Collection<String>? = { listOf(it) },
    ): Decision {
        val push = LinkedHashSet<String>()
        PushDirectPackages.packages.forEach { pkg ->
            if (pkg.isNotBlank()) push += pkg
        }
        val userDirect = LinkedHashSet<String>()
        directApps.forEach { pkg ->
            val trimmed = pkg.trim()
            if (trimmed.isNotBlank()) userDirect += trimmed
        }
        val bypass = LinkedHashSet<String>().apply {
            addAll(push)
            addAll(userDirect)
        }
        val vpn = vpnApps.map { it.trim() }.filter { it.isNotBlank() }.toMutableSet()
        vpn.removeAll(push)

        fun uidPackagesOf(pkg: String): List<String> {
            return packagesSharingUid(pkg)
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.distinct()
                .orEmpty()
        }

        val routeDirect = LinkedHashSet<String>()
        val excluded = LinkedHashSet<String>()
        val skipped = mutableListOf<Skip>()

        for (pkg in bypass) {
            val uidPackages = uidPackagesOf(pkg)
            if (uidPackages.isEmpty()) {
                // Fail closed: without UID ownership a SMART/VPN sibling could
                // ride the same app DIRECT rule. Keep the UID in TUN and let
                // domain / automatic rules decide.
                skipped += Skip(pkg, SKIP_UNRESOLVED, emptyList())
                continue
            }
            val vpnHit = uidPackages.firstOrNull { it in vpn }
            if (vpnHit != null) {
                skipped += Skip(pkg, SKIP_MIXED_VPN, uidPackages)
                continue
            }
            val smartHit = uidPackages.firstOrNull { it !in bypass }
            if (smartHit != null) {
                skipped += Skip(pkg, SKIP_MIXED_SMART, uidPackages)
                continue
            }
            if (pkg in userDirect) routeDirect += pkg
            if (excludeDirectFromTun) excluded.addAll(uidPackages)
        }

        return Decision(
            packages = excluded.toList(),
            routeDirectApps = routeDirect.toList(),
            skipped = skipped,
        )
    }
}
