package com.layer.core.routing

import com.layer.core.model.AppRoutingMode
import com.layer.core.model.AppRoutingRule
import com.layer.core.model.DomainRoutingMode
import com.layer.core.model.DomainRoutingRule
import com.layer.core.model.RoutingOutbound
import com.layer.core.model.RoutingReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingEngineTest {

    private val youtubeApp = AppRoutingRule("com.google.android.youtube", "YouTube", AppRoutingMode.SMART)
    private val chromeApp = AppRoutingRule("com.android.chrome", "Chrome", AppRoutingMode.SMART)
    private val bankApp = AppRoutingRule("ru.bank.app", "Банк", AppRoutingMode.DIRECT)
    private val vpnApp = AppRoutingRule("org.telegram.messenger", "Telegram", AppRoutingMode.VPN)

    private val youtubeDomain = DomainRoutingRule("youtube.com", DomainRoutingMode.VPN)
    private val googleDomain = DomainRoutingRule("google.com", DomainRoutingMode.VPN)
    private val exampleDirect = DomainRoutingRule("example.ru", DomainRoutingMode.DIRECT)

    @Test
    fun defaultIsDirect() {
        val decision = RoutingEngine.decide(domain = "wildberries.ru")
        assertEquals(RoutingOutbound.DIRECT, decision.outbound)
        assertEquals(RoutingReason.DEFAULT_DIRECT, decision.reason)
    }

    @Test
    fun automaticBlockedDomainGoesVpn() {
        val decision = RoutingEngine.decide(
            domain = "youtube.com",
            automaticVpnMatch = true,
        )
        assertEquals(RoutingOutbound.VPN, decision.outbound)
        assertEquals(RoutingReason.AUTOMATIC_RULE_SET, decision.reason)
    }

    @Test
    fun manualDomainVpnGoesVpn() {
        val decision = RoutingEngine.decide(
            domain = "youtube.com",
            domainRules = listOf(youtubeDomain),
        )
        assertEquals(RoutingOutbound.VPN, decision.outbound)
        assertEquals(RoutingReason.USER_DOMAIN_VPN, decision.reason)
    }

    @Test
    fun manualDomainDirectGoesDirectEvenIfAutomaticSaysVpn() {
        val decision = RoutingEngine.decide(
            domain = "example.com",
            domainRules = listOf(DomainRoutingRule("example.com", DomainRoutingMode.DIRECT)),
            automaticVpnMatch = true,
        )
        assertEquals(RoutingOutbound.DIRECT, decision.outbound)
        assertEquals(RoutingReason.USER_DOMAIN_DIRECT, decision.reason)
    }

    @Test
    fun appVpnSendsAllTrafficToVpn() {
        val decision = RoutingEngine.decide(
            packageName = vpnApp.packageName,
            domain = "wildberries.ru",
            appRules = listOf(vpnApp),
        )
        assertEquals(RoutingOutbound.VPN, decision.outbound)
        assertEquals(RoutingReason.APP_VPN, decision.reason)
    }

    @Test
    fun appDirectSendsAllTrafficDirect() {
        val decision = RoutingEngine.decide(
            packageName = bankApp.packageName,
            domain = "youtube.com",
            appRules = listOf(bankApp),
            domainRules = listOf(youtubeDomain),
            automaticVpnMatch = true,
        )
        assertEquals(RoutingOutbound.DIRECT, decision.outbound)
        assertEquals(RoutingReason.APP_DIRECT, decision.reason)
    }

    @Test
    fun smartAppPlusVpnDomainGoesVpn() {
        val decision = RoutingEngine.decide(
            packageName = chromeApp.packageName,
            domain = "youtube.com",
            appRules = listOf(chromeApp),
            domainRules = listOf(youtubeDomain, googleDomain),
        )
        assertEquals(RoutingOutbound.VPN, decision.outbound)
        assertEquals(RoutingReason.USER_DOMAIN_VPN, decision.reason)
    }

    @Test
    fun smartAppPlusNormalDomainGoesDirect() {
        val decision = RoutingEngine.decide(
            packageName = chromeApp.packageName,
            domain = "wildberries.ru",
            appRules = listOf(chromeApp, youtubeApp),
            domainRules = listOf(youtubeDomain, googleDomain, exampleDirect),
        )
        assertEquals(RoutingOutbound.DIRECT, decision.outbound)
        assertEquals(RoutingReason.DEFAULT_DIRECT, decision.reason)
    }

    @Test
    fun appDirectPlusVpnDomainStaysDirect() {
        val chromeDirect = chromeApp.copy(mode = AppRoutingMode.DIRECT)
        val decision = RoutingEngine.decide(
            packageName = chromeDirect.packageName,
            domain = "youtube.com",
            appRules = listOf(chromeDirect),
            domainRules = listOf(youtubeDomain),
        )
        assertEquals(RoutingOutbound.DIRECT, decision.outbound)
        assertEquals(RoutingReason.APP_DIRECT, decision.reason)
    }

    @Test
    fun deletingAppRuleReturnsDefaultBehaviour() {
        val remaining = RoutingEngine.withoutAppRule(listOf(vpnApp, chromeApp), vpnApp.packageName)
        val decision = RoutingEngine.decide(
            packageName = vpnApp.packageName,
            domain = "example.org",
            appRules = remaining,
        )
        assertEquals(RoutingOutbound.DIRECT, decision.outbound)
        assertEquals(RoutingReason.DEFAULT_DIRECT, decision.reason)
    }

    @Test
    fun deletingDomainRuleReturnsAutomaticOrDefault() {
        val remaining = RoutingEngine.withoutDomainRule(listOf(youtubeDomain), youtubeDomain.domain)
        val automatic = RoutingEngine.decide(
            domain = "youtube.com",
            domainRules = remaining,
            automaticVpnMatch = true,
        )
        assertEquals(RoutingOutbound.VPN, automatic.outbound)
        assertEquals(RoutingReason.AUTOMATIC_RULE_SET, automatic.reason)

        val fallback = RoutingEngine.decide(
            domain = "youtube.com",
            domainRules = remaining,
            automaticVpnMatch = false,
        )
        assertEquals(RoutingOutbound.DIRECT, fallback.outbound)
    }

    @Test
    fun userDirectBeatsAutomaticVpn() {
        val decision = RoutingEngine.decide(
            domain = "instagram.com",
            domainRules = listOf(DomainRoutingRule("instagram.com", DomainRoutingMode.DIRECT)),
            automaticVpnMatch = true,
        )
        assertEquals(RoutingOutbound.DIRECT, decision.outbound)
        assertEquals(RoutingReason.USER_DOMAIN_DIRECT, decision.reason)
    }

    @Test
    fun localTrafficIsDirectWhenSmart() {
        val decision = RoutingEngine.decide(
            domain = "router.local",
            isLocalTraffic = true,
        )
        assertEquals(RoutingOutbound.DIRECT, decision.outbound)
        assertEquals(RoutingReason.LOCAL_TRAFFIC, decision.reason)
    }

    @Test
    fun suffixDomainRulesMatchSubdomains() {
        val decision = RoutingEngine.decide(
            domain = "gemini.google.com",
            domainRules = listOf(googleDomain),
        )
        assertEquals(RoutingOutbound.VPN, decision.outbound)
        assertTrue(decision.usesVpn)
    }

    @Test
    fun appVpnIgnoresLowerPriorityDomainDirect() {
        val decision = RoutingEngine.decide(
            packageName = vpnApp.packageName,
            domain = "example.ru",
            appRules = listOf(vpnApp),
            domainRules = listOf(exampleDirect),
        )
        assertEquals(RoutingOutbound.VPN, decision.outbound)
        assertFalse(decision.reason == RoutingReason.USER_DOMAIN_DIRECT)
    }
}
