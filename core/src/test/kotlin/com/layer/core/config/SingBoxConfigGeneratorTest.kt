package com.layer.core.config

import com.layer.core.model.AppRoutingMode
import com.layer.core.model.AppRoutingRule
import com.layer.core.model.DomainRoutingMode
import com.layer.core.model.DomainRoutingRule
import com.layer.core.model.LayerSettings
import com.layer.core.model.UUID_PLACEHOLDER
import com.layer.core.model.VlessServerConfig
import com.layer.core.i18n.UiLanguage
import com.layer.core.i18n.UiLanguageState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SingBoxConfigGeneratorTest {

    private val sampleUuid = "11111111-2222-3333-4444-555555555555"
    private val sampleSettings = LayerSettings(
        server = VlessServerConfig(address = "vpn.example.com"),
    )

    @Before
    fun useRussianCopy() {
        UiLanguageState.current = UiLanguage.RUSSIAN
    }

    @Test
    fun refusesPlaceholderUuid() {
        val result = SingBoxConfigGenerator.generate(
            uuid = UUID_PLACEHOLDER,
            settings = LayerSettings(),
            appRules = emptyList(),
            domainRules = emptyList(),
            ownPackageName = "com.layer.app",
        )
        assertFalse(result.isSuccess)
    }

    @Test
    fun refusesBlankServerAddress() {
        val result = SingBoxConfigGenerator.generate(
            uuid = sampleUuid,
            settings = LayerSettings(),
            appRules = emptyList(),
            domainRules = emptyList(),
            ownPackageName = "com.layer.app",
        )
        assertFalse(result.isSuccess)
        assertTrue(result.error.orEmpty().contains("сервер"))
    }

    @Test
    fun generatedRulesFollowPriorityOrder() {
        val json = SingBoxConfigGenerator.generate(
            uuid = sampleUuid,
            settings = sampleSettings,
            appRules = listOf(
                AppRoutingRule("ru.bank.app", "Банк", AppRoutingMode.DIRECT),
                AppRoutingRule("com.google.android.youtube", "YouTube", AppRoutingMode.VPN),
                AppRoutingRule("com.android.chrome", "Chrome", AppRoutingMode.SMART),
            ),
            domainRules = listOf(
                DomainRoutingRule("example.ru", DomainRoutingMode.DIRECT),
                DomainRoutingRule("youtube.com", DomainRoutingMode.VPN),
            ),
            ownPackageName = "com.layer.app",
        ).json

        val root = Json.parseToJsonElement(json).jsonObject
        val rules = root["route"]!!.jsonObject["rules"]!!.jsonArray
        val serialized = rules.map { it.toString() }
        val directApp = serialized.indexOfFirst { it.contains("ru.bank.app") }
        val vpnApp = serialized.indexOfFirst { it.contains("com.google.android.youtube") }
        val directDomain = serialized.indexOfFirst { it.contains("example.ru") }
        val vpnDomain = serialized.indexOfFirst { it.contains("youtube.com") }
        val automatic = serialized.indexOfFirst { it.contains("rs-youtube") }

        assertTrue(directApp >= 0)
        assertTrue(vpnApp > directApp)
        assertTrue(directDomain > vpnApp)
        assertTrue(vpnDomain > directDomain)
        assertTrue(automatic > vpnDomain)
        assertEquals("direct", root["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
        assertTrue(json.contains("xtls-rprx-vision"))
        assertTrue(json.contains("firefox"))
        assertTrue(json.contains("http/1.1"))
        assertTrue(json.contains("vpn.example.com"))
        assertTrue(json.contains("\"action\": \"hijack-dns\""))
        assertTrue(json.contains("\"action\": \"sniff\""))
        assertTrue(json.contains("\"stack\": \"gvisor\""))
        assertTrue(json.contains("0.0.0.0/0"))
        assertTrue(json.contains("\"address\""))
        assertTrue(json.contains("domain_resolver"))
        assertFalse(json.contains("inet4_address"))
        assertFalse(json.contains("\"type\": \"block\""))
        assertFalse(json.contains("\"type\": \"dns\""))
        assertFalse(json.contains("\"network\": \"tcp\""))
        assertFalse(json.contains("\"network\": \"udp\""))
        assertTrue(json.contains("packet_encoding"))
        assertTrue(json.contains("\"level\": \"warn\""))
        assertTrue(json.contains("127.0.0.0/8"))
        assertTrue(json.contains("198.18.0.0/15"))
        assertTrue(json.contains("8.8.4.4/32"))
        assertTrue(json.contains("1.1.1.1/32"))
        assertTrue(json.contains("\"protocol\": \"quic\""))
        assertTrue(json.contains("\"udp_timeout\": \"5m\""))
        assertTrue(json.contains("\"tcp_keep_alive\": \"60s\""))
        assertTrue(json.contains("\"tcp_keep_alive_interval\": \"60s\""))
        assertTrue(json.contains("\"connect_timeout\": \"15s\""))
        assertFalse(json.contains(UUID_PLACEHOLDER))

        val sniffIndex = rules.indexOfFirst {
            it.jsonObject["action"]?.jsonPrimitive?.content == "sniff"
        }
        val dohReject = rules.indexOfFirst { rule ->
            val obj = rule.jsonObject
            obj["action"]?.jsonPrimitive?.content == "reject" &&
                obj["port"]?.jsonPrimitive?.content == "443" &&
                obj["ip_cidr"]?.toString().orEmpty().contains("8.8.4.4/32")
        }
        val quicReject = rules.indexOfFirst { rule ->
            val obj = rule.jsonObject
            obj["protocol"]?.jsonPrimitive?.content == "quic" &&
                obj["action"]?.jsonPrimitive?.content == "reject" &&
                obj.containsKey("rule_set")
        }
        val proxyList = rules.indexOfFirst { rule ->
            val obj = rule.jsonObject
            obj["outbound"]?.jsonPrimitive?.content == "proxy" &&
                obj.containsKey("rule_set")
        }
        assertTrue(sniffIndex >= 0)
        assertTrue(dohReject > sniffIndex)
        assertTrue(vpnApp > dohReject)
        assertTrue(quicReject > sniffIndex)
        assertTrue(proxyList > quicReject)
        assertEquals("5m", rules[proxyList].jsonObject["udp_timeout"]!!.jsonPrimitive.content)
    }

    @Test
    fun playServicesAlwaysDirectSoPushBypassesVless() {
        val json = SingBoxConfigGenerator.generate(
            uuid = sampleUuid,
            settings = sampleSettings.copy(automaticRuleSetEnabled = true),
            appRules = listOf(
                AppRoutingRule("com.google.android.gms", "Play Services", AppRoutingMode.VPN),
            ),
            domainRules = emptyList(),
            ownPackageName = "com.layer.app",
        ).json
        val rules = Json.parseToJsonElement(json).jsonObject["route"]!!.jsonObject["rules"]!!.jsonArray
        val serialized = rules.map { it.toString() }
        val pushDirect = serialized.indexOfFirst {
            it.contains("com.google.android.gms") &&
                it.contains("com.google.android.gsf") &&
                it.contains("\"direct\"")
        }
        val userVpnGms = serialized.indexOfFirst {
            it.contains("com.google.android.gms") && it.contains("\"proxy\"")
        }
        val playList = serialized.indexOfFirst { it.contains("rs-google-play") }
        assertTrue(pushDirect >= 0)
        assertTrue(userVpnGms > pushDirect)
        assertTrue(playList > pushDirect)
    }

    @Test
    fun serverIsForcedDirectToPreventRoutingLoop() {
        val json = SingBoxConfigGenerator.generate(
            uuid = sampleUuid,
            settings = sampleSettings,
            appRules = emptyList(),
            domainRules = emptyList(),
            ownPackageName = "com.layer.app",
        ).json
        assertTrue(json.contains("vpn.example.com"))
        assertTrue(json.contains("ip_is_private"))
        assertTrue(json.contains("\"tag\": \"proxy\""))
    }

    @Test
    fun resolvedServerIpIsUsedForDialKeepSniHostname() {
        val json = SingBoxConfigGenerator.generate(
            uuid = sampleUuid,
            settings = sampleSettings,
            appRules = emptyList(),
            domainRules = emptyList(),
            ownPackageName = "com.layer.app",
            resolvedServerIp = "203.0.113.10",
        ).json
        val root = Json.parseToJsonElement(json).jsonObject
        val proxy = root["outbounds"]!!.jsonArray.first().jsonObject
        assertEquals("203.0.113.10", proxy["server"]!!.jsonPrimitive.content)
        assertEquals(
            "vpn.example.com",
            proxy["tls"]!!.jsonObject["server_name"]!!.jsonPrimitive.content,
        )
        assertTrue(json.contains("203.0.113.10/32"))
        assertTrue(json.contains("ipv4_only"))
        val dnsDirect = root["dns"]!!.jsonObject["servers"]!!.jsonArray
            .first { it.jsonObject["tag"]!!.jsonPrimitive.content == "dns-direct" }
            .jsonObject
        assertFalse(dnsDirect.containsKey("detour"))
        val dnsServers = root["dns"]!!.jsonObject["servers"]!!.jsonArray
        assertFalse(dnsServers.any { it.jsonObject["tag"]?.jsonPrimitive?.content == "dns-vpn" })
        assertTrue(json.contains("\"download_detour\": \"proxy\""))
    }

    @Test
    fun vpnListDnsGoesDirectNotThroughVision() {
        val json = SingBoxConfigGenerator.generate(
            uuid = sampleUuid,
            settings = sampleSettings,
            appRules = emptyList(),
            domainRules = listOf(
                DomainRoutingRule("youtube.com", DomainRoutingMode.VPN),
            ),
            ownPackageName = "com.layer.app",
        ).json
        val root = Json.parseToJsonElement(json).jsonObject
        val dnsRules = root["dns"]!!.jsonObject["rules"]!!.jsonArray
        val vpnDns = dnsRules.first { rule ->
            rule.jsonObject["domain_suffix"]?.toString().orEmpty().contains("youtube.com")
        }.jsonObject
        assertEquals("dns-direct", vpnDns["server"]!!.jsonPrimitive.content)
        val autoDns = dnsRules.first { it.jsonObject.containsKey("rule_set") }.jsonObject
        assertEquals("dns-direct", autoDns["server"]!!.jsonPrimitive.content)
        assertFalse(json.contains("\"detour\": \"proxy\""))
    }

    @Test
    fun realityOutboundUsesPublicKeyAndOmitsAlpn() {
        val json = SingBoxConfigGenerator.generate(
            uuid = sampleUuid,
            settings = LayerSettings(
                server = VlessServerConfig(
                    address = "203.0.113.10",
                    serverName = "www.example.com",
                    fingerprint = "chrome",
                    security = "reality",
                    publicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                    shortId = "abcd1234efgh",
                    spiderX = "/",
                ),
            ),
            appRules = emptyList(),
            domainRules = emptyList(),
            ownPackageName = "com.layer.app",
        ).json
        assertTrue(json.contains("\"reality\""))
        assertTrue(json.contains("www.example.com"))
        assertTrue(json.contains("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
        assertFalse(json.contains("\"alpn\""))
        assertFalse(json.contains("spider_x"))
    }

    @Test
    fun realityWithoutFingerprintUsesChrome() {
        val json = SingBoxConfigGenerator.generate(
            uuid = sampleUuid,
            settings = LayerSettings(
                server = VlessServerConfig(
                    address = "203.0.113.10",
                    serverName = "www.example.com",
                    fingerprint = "",
                    security = "reality",
                    publicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                    shortId = "abcd1234efgh",
                ),
            ),
            appRules = emptyList(),
            domainRules = emptyList(),
            ownPackageName = "com.layer.app",
        ).json
        assertTrue(json.contains("chrome"))
        assertFalse(json.contains("firefox"))
    }

    @Test
    fun realityPublicKeyIsNormalizedToRawUrl() {
        val json = SingBoxConfigGenerator.generate(
            uuid = sampleUuid,
            settings = LayerSettings(
                server = VlessServerConfig(
                    address = "203.0.113.10",
                    serverName = "www.example.com",
                    fingerprint = "chrome",
                    security = "reality",
                    publicKey = "abc de/fg==",
                    shortId = "abcd1234efgh",
                ),
            ),
            appRules = emptyList(),
            domainRules = emptyList(),
            ownPackageName = "com.layer.app",
        ).json
        assertTrue(json.contains("abc-de_fg"))
        assertFalse(json.contains("abc de/fg=="))
        assertFalse(json.contains("abc+de/fg"))
    }

    @Test
    fun xhttpRealityOmitsVisionAndEmitsTransport() {
        val json = SingBoxConfigGenerator.generate(
            uuid = sampleUuid,
            settings = LayerSettings(
                server = VlessServerConfig(
                    address = "203.0.113.10",
                    serverName = "www.example.com",
                    fingerprint = "edge",
                    security = "reality",
                    publicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                    shortId = "abcd1234",
                    network = "xhttp",
                    flow = "xtls-rprx-vision",
                    path = "/",
                    httpHost = "www.example.com",
                    transportMode = "auto",
                    xPaddingBytes = "100-1000",
                ),
            ),
            appRules = emptyList(),
            domainRules = emptyList(),
            ownPackageName = "com.layer.app",
        ).json
        val root = Json.parseToJsonElement(json).jsonObject
        val proxy = root["outbounds"]!!.jsonArray.first().jsonObject
        assertFalse(proxy.containsKey("flow"))
        val transport = proxy["transport"]!!.jsonObject
        assertEquals("xhttp", transport["type"]!!.jsonPrimitive.content)
        assertEquals("www.example.com", transport["host"]!!.jsonPrimitive.content)
        assertEquals("/", transport["path"]!!.jsonPrimitive.content)
        assertEquals("auto", transport["mode"]!!.jsonPrimitive.content)
        assertEquals("100-1000", transport["x_padding_bytes"]!!.jsonPrimitive.content)
        assertTrue(json.contains("\"reality\""))
        assertFalse(json.contains("xtls-rprx-vision"))
    }

    @Test
    fun missingRuleSetsFallBackToProxyDownload() {
        val json = SingBoxConfigGenerator.generate(
            uuid = sampleUuid,
            settings = sampleSettings,
            appRules = emptyList(),
            domainRules = emptyList(),
            ownPackageName = "com.layer.app",
        ).json
        val ruleSets = Json.parseToJsonElement(json).jsonObject["route"]!!.jsonObject["rule_set"]!!.jsonArray
        assertTrue(ruleSets.isNotEmpty())
        ruleSets.forEach { item ->
            val obj = item.jsonObject
            assertEquals("remote", obj["type"]!!.jsonPrimitive.content)
            assertEquals("proxy", obj["download_detour"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun localRuleSetsSkipRemoteDownload() {
        val json = SingBoxConfigGenerator.generate(
            uuid = sampleUuid,
            settings = sampleSettings,
            appRules = emptyList(),
            domainRules = emptyList(),
            ownPackageName = "com.layer.app",
            localRuleSets = mapOf("rs-youtube" to "/data/rule-sets/rs-youtube.srs"),
        ).json
        val ruleSets = Json.parseToJsonElement(json).jsonObject["route"]!!.jsonObject["rule_set"]!!.jsonArray
        val youtube = ruleSets.first { it.jsonObject["tag"]!!.jsonPrimitive.content == "rs-youtube" }.jsonObject
        val meta = ruleSets.first { it.jsonObject["tag"]!!.jsonPrimitive.content == "rs-meta" }.jsonObject
        assertEquals("local", youtube["type"]!!.jsonPrimitive.content)
        assertEquals("/data/rule-sets/rs-youtube.srs", youtube["path"]!!.jsonPrimitive.content)
        assertEquals("remote", meta["type"]!!.jsonPrimitive.content)
        assertEquals("proxy", meta["download_detour"]!!.jsonPrimitive.content)
    }

    @Test
    fun missingRuleSetsCanBeOmittedSoVpnStartsWithoutProxyDownload() {
        val json = SingBoxConfigGenerator.generate(
            uuid = sampleUuid,
            settings = sampleSettings,
            appRules = emptyList(),
            domainRules = emptyList(),
            ownPackageName = "com.layer.app",
            remoteRuleSetFallback = false,
        ).json
        val ruleSets = Json.parseToJsonElement(json).jsonObject["route"]!!.jsonObject["rule_set"]!!.jsonArray
        assertTrue(ruleSets.isEmpty())
        assertFalse(json.contains("download_detour"))
        assertFalse(json.contains("rs-youtube"))
    }

    @Test
    fun adBlockRejectsWhenEnabledAndFilePresent() {
        val json = SingBoxConfigGenerator.generate(
            uuid = sampleUuid,
            settings = sampleSettings.copy(adBlockEnabled = true),
            appRules = emptyList(),
            domainRules = listOf(
                DomainRoutingRule("example.ru", DomainRoutingMode.DIRECT),
            ),
            ownPackageName = "com.layer.app",
            adBlockRuleSetPath = "/data/adblock/dns-ad-filter.json",
        ).json
        val root = Json.parseToJsonElement(json).jsonObject
        val ruleSets = root["route"]!!.jsonObject["rule_set"]!!.jsonArray
        val ads = ruleSets.first { it.jsonObject["tag"]!!.jsonPrimitive.content == "rs-ads" }.jsonObject
        assertEquals("local", ads["type"]!!.jsonPrimitive.content)
        assertEquals("source", ads["format"]!!.jsonPrimitive.content)
        assertEquals("/data/adblock/dns-ad-filter.json", ads["path"]!!.jsonPrimitive.content)

        val routeRules = root["route"]!!.jsonObject["rules"]!!.jsonArray
        val serialized = routeRules.map { it.toString() }
        val userDirect = serialized.indexOfFirst { it.contains("example.ru") }
        val adsReject = routeRules.indexOfFirst { rule ->
            val obj = rule.jsonObject
            obj["action"]?.jsonPrimitive?.content == "reject" &&
                obj["rule_set"]?.toString().orEmpty().contains("rs-ads")
        }
        val automatic = serialized.indexOfFirst { it.contains("rs-youtube") && it.contains("proxy") }
        assertTrue(userDirect >= 0)
        assertTrue(adsReject > userDirect)
        assertTrue(automatic > adsReject)

        val dnsAds = root["dns"]!!.jsonObject["rules"]!!.jsonArray.first { rule ->
            val obj = rule.jsonObject
            obj["action"]?.jsonPrimitive?.content == "reject" &&
                obj["rule_set"]?.toString().orEmpty().contains("rs-ads")
        }.jsonObject
        assertEquals("reject", dnsAds["action"]!!.jsonPrimitive.content)
    }

    @Test
    fun adBlockOmittedWhenDisabledEvenIfFilePresent() {
        val json = SingBoxConfigGenerator.generate(
            uuid = sampleUuid,
            settings = sampleSettings.copy(adBlockEnabled = false),
            appRules = emptyList(),
            domainRules = emptyList(),
            ownPackageName = "com.layer.app",
            adBlockRuleSetPath = "/data/adblock/dns-ad-filter.json",
        ).json
        assertFalse(json.contains("rs-ads"))
        assertFalse(json.contains("dns-ad-filter.json"))
    }

    @Test
    fun adBlockOmittedWhenEnabledButFileMissing() {
        val json = SingBoxConfigGenerator.generate(
            uuid = sampleUuid,
            settings = sampleSettings.copy(adBlockEnabled = true),
            appRules = emptyList(),
            domainRules = emptyList(),
            ownPackageName = "com.layer.app",
            adBlockRuleSetPath = null,
        ).json
        assertFalse(json.contains("rs-ads"))
    }
}
