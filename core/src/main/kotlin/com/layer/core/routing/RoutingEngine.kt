package com.layer.core.routing

import com.layer.core.model.AppRoutingMode
import com.layer.core.model.AppRoutingRule
import com.layer.core.model.DomainRoutingMode
import com.layer.core.model.DomainRoutingRule
import com.layer.core.model.RoutingDecision
import com.layer.core.model.RoutingOutbound
import com.layer.core.model.RoutingReason

/**
 * Pure routing engine. The same priority is encoded into generated sing-box JSON.
 *
 * App-level DIRECT/VPN always win over domain rules. SMART (or no app rule) uses
 * user domain rules, then the automatic rule-set, then DIRECT.
 */
object RoutingEngine {

    fun decide(
        packageName: String? = null,
        domain: String? = null,
        appRules: List<AppRoutingRule> = emptyList(),
        domainRules: List<DomainRoutingRule> = emptyList(),
        automaticVpnMatch: Boolean = false,
        isLocalTraffic: Boolean = false,
    ): RoutingDecision {
        val appMode = packageName
            ?.let { pkg -> appRules.firstOrNull { it.packageName == pkg }?.mode }
            ?: AppRoutingMode.SMART

        when (appMode) {
            AppRoutingMode.DIRECT -> {
                return RoutingDecision(RoutingOutbound.DIRECT, RoutingReason.APP_DIRECT)
            }
            AppRoutingMode.VPN -> {
                return RoutingDecision(RoutingOutbound.VPN, RoutingReason.APP_VPN)
            }
            AppRoutingMode.SMART -> Unit
        }

        val matchedDomainRule = domain?.let { host ->
            domainRules.firstOrNull { HostnameNormalizer.matches(it.domain, host) }
        }
        when (matchedDomainRule?.mode) {
            DomainRoutingMode.DIRECT -> {
                return RoutingDecision(RoutingOutbound.DIRECT, RoutingReason.USER_DOMAIN_DIRECT)
            }
            DomainRoutingMode.VPN -> {
                return RoutingDecision(RoutingOutbound.VPN, RoutingReason.USER_DOMAIN_VPN)
            }
            null -> Unit
        }

        if (automaticVpnMatch) {
            return RoutingDecision(RoutingOutbound.VPN, RoutingReason.AUTOMATIC_RULE_SET)
        }
        if (isLocalTraffic) {
            return RoutingDecision(RoutingOutbound.DIRECT, RoutingReason.LOCAL_TRAFFIC)
        }
        return RoutingDecision(RoutingOutbound.DIRECT, RoutingReason.DEFAULT_DIRECT)
    }

    fun withoutAppRule(
        rules: List<AppRoutingRule>,
        packageName: String,
    ): List<AppRoutingRule> = rules.filterNot { it.packageName == packageName }

    fun withoutDomainRule(
        rules: List<DomainRoutingRule>,
        domain: String,
    ): List<DomainRoutingRule> = rules.filterNot { it.domain == domain }
}
