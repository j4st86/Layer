package com.layer.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunDirectExcludePolicyTest {
    @Test
    fun emptyWhenExclusionDisabled() {
        val decision = TunDirectExcludePolicy.decide(
            excludeDirectFromTun = false,
            directApps = listOf("ru.ozon.app.android"),
        )
        assertEquals(emptyList<String>(), decision.packages)
        assertEquals(listOf("ru.ozon.app.android"), decision.routeDirectApps)
    }

    @Test
    fun includesPushAndUserDirectNotVpnOrSmart() {
        val packages = TunDirectExcludePolicy.packages(
            excludeDirectFromTun = true,
            directApps = listOf("ru.ozon.app.android", " ru.sberbankmobile "),
            vpnApps = listOf(
                "org.telegram.messenger",
                "com.google.android.youtube",
                "com.android.vending",
            ),
        )
        assertEquals(
            listOf(
                "com.google.android.gms",
                "com.google.android.gsf",
                "ru.ozon.app.android",
                "ru.sberbankmobile",
            ),
            packages,
        )
        assertFalse(packages.contains("org.telegram.messenger"))
        assertFalse(packages.contains("com.google.android.youtube"))
        assertFalse(packages.contains("com.android.vending"))
        assertFalse(packages.contains("com.android.chrome"))
    }

    @Test
    fun stillExcludesGmsIfUserMarkedItVpn() {
        val packages = TunDirectExcludePolicy.packages(
            excludeDirectFromTun = true,
            directApps = emptyList(),
            vpnApps = listOf("com.google.android.gms"),
        )
        assertTrue(packages.contains("com.google.android.gms"))
        assertTrue(packages.contains("com.google.android.gsf"))
    }

    @Test
    fun dropsBlankAndDeduplicates() {
        assertEquals(
            listOf("com.google.android.gms", "com.google.android.gsf", "ru.ozon.app.android"),
            TunDirectExcludePolicy.packages(
                excludeDirectFromTun = true,
                directApps = listOf("", "ru.ozon.app.android", "com.google.android.gms"),
            ),
        )
    }

    @Test
    fun keepsSharedUidInTunWhenASmartBrowserIsOnIt() {
        val decision = TunDirectExcludePolicy.decide(
            excludeDirectFromTun = true,
            directApps = listOf("ru.bank.app"),
            vpnApps = emptyList(),
            packagesSharingUid = { pkg ->
                when (pkg) {
                    "ru.bank.app", "com.android.chrome" ->
                        listOf("ru.bank.app", "com.android.chrome")
                    else -> listOf(pkg)
                }
            },
        )
        assertEquals(
            listOf("com.google.android.gms", "com.google.android.gsf"),
            decision.packages,
        )
        assertTrue(
            decision.skipped.any {
                it.packageName == "ru.bank.app" &&
                    it.reason == TunDirectExcludePolicy.SKIP_MIXED_SMART &&
                    it.uidPackages.contains("com.android.chrome")
            },
        )
        assertFalse(decision.routeDirectApps.contains("ru.bank.app"))
    }

    @Test
    fun keepsSharedUidInTunWhenAVpnAppIsOnIt() {
        val decision = TunDirectExcludePolicy.decide(
            excludeDirectFromTun = true,
            directApps = listOf("com.termux"),
            vpnApps = listOf("com.termux.styling"),
            packagesSharingUid = { pkg ->
                when (pkg) {
                    "com.termux", "com.termux.styling" ->
                        listOf("com.termux", "com.termux.styling")
                    else -> listOf(pkg)
                }
            },
        )
        assertFalse(decision.packages.contains("com.termux"))
        assertTrue(
            decision.skipped.any {
                it.packageName == "com.termux" &&
                    it.reason == TunDirectExcludePolicy.SKIP_MIXED_VPN
            },
        )
        assertFalse(decision.routeDirectApps.contains("com.termux"))
    }

    @Test
    fun excludesWholeUidWhenEveryPackageIsBypass() {
        val packages = TunDirectExcludePolicy.packages(
            excludeDirectFromTun = true,
            directApps = emptyList(),
            packagesSharingUid = { pkg ->
                when (pkg) {
                    "com.google.android.gms", "com.google.android.gsf" ->
                        listOf("com.google.android.gms", "com.google.android.gsf")
                    else -> listOf(pkg)
                }
            },
        )
        assertEquals(
            listOf("com.google.android.gms", "com.google.android.gsf"),
            packages,
        )
    }

    @Test
    fun unlistedBrowserIsNotABypassCandidate() {
        val packages = TunDirectExcludePolicy.packages(
            excludeDirectFromTun = true,
            directApps = listOf("ru.bank.app"),
            vpnApps = listOf("org.telegram.messenger"),
        )
        assertFalse(packages.contains("org.mozilla.firefox"))
        assertFalse(packages.contains("com.android.chrome"))
        assertTrue(packages.contains("ru.bank.app"))
    }

    @Test
    fun skipsUnresolvedUidLookups() {
        val decision = TunDirectExcludePolicy.decide(
            excludeDirectFromTun = true,
            directApps = listOf("ru.missing.app"),
            packagesSharingUid = { pkg ->
                if (pkg == "ru.missing.app") null else listOf(pkg)
            },
        )
        assertFalse(decision.packages.contains("ru.missing.app"))
        assertEquals(
            TunDirectExcludePolicy.SKIP_UNRESOLVED,
            decision.skipped.single { it.packageName == "ru.missing.app" }.reason,
        )
        assertFalse(decision.routeDirectApps.contains("ru.missing.app"))
    }

    @Test
    fun twoDirectPackagesOnTheSameUidStayDirect() {
        val decision = TunDirectExcludePolicy.decide(
            excludeDirectFromTun = true,
            directApps = listOf("com.bank.main", "com.bank.plugin"),
            packagesSharingUid = { pkg ->
                when (pkg) {
                    "com.bank.main", "com.bank.plugin" ->
                        listOf("com.bank.main", "com.bank.plugin")
                    else -> listOf(pkg)
                }
            },
        )
        assertTrue(decision.packages.containsAll(listOf("com.bank.main", "com.bank.plugin")))
        assertEquals(listOf("com.bank.main", "com.bank.plugin"), decision.routeDirectApps)
    }

    @Test
    fun mixedSmartUidIsDroppedFromRouteDirectEvenWhenExclusionIsOff() {
        val decision = TunDirectExcludePolicy.decide(
            excludeDirectFromTun = false,
            directApps = listOf("ru.bank.app"),
            packagesSharingUid = { pkg ->
                when (pkg) {
                    "ru.bank.app", "com.android.chrome" ->
                        listOf("ru.bank.app", "com.android.chrome")
                    else -> listOf(pkg)
                }
            },
        )
        assertEquals(emptyList<String>(), decision.packages)
        assertFalse(decision.routeDirectApps.contains("ru.bank.app"))
    }
}
