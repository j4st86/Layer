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
import kotlinx.serialization.json.JsonObject
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
        assertFalse(json.contains("exclude_package"))
        assertFalse(json.contains("include_package"))
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
        val dnsServers = root["dns"]!!.jsonObject["servers"]!!.jsonArray
        val dnsDirect = dnsServers
            .first { it.jsonObject["tag"]!!.jsonPrimitive.content == "dns-direct" }
            .jsonObject
        assertEquals("local", dnsDirect["type"]!!.jsonPrimitive.content)
        assertFalse(dnsDirect.containsKey("detour"))
        val dnsProxy = dnsServers
            .first { it.jsonObject["tag"]!!.jsonPrimitive.content == "dns-proxy" }
            .jsonObject
        assertEquals("udp", dnsProxy["type"]!!.jsonPrimitive.content)
        assertEquals("8.8.8.8", dnsProxy["server"]!!.jsonPrimitive.content)
        assertEquals("proxy", dnsProxy["detour"]!!.jsonPrimitive.content)
        assertEquals("dns-direct", root["dns"]!!.jsonObject["final"]!!.jsonPrimitive.content)
        assertTrue(json.contains("\"download_detour\": \"proxy\""))
    }

    @Test
    fun dnsFollowsAppAndDomainRoutingWithoutAppDoh() {
        val json = SingBoxConfigGenerator.generate(
            uuid = sampleUuid,
            settings = sampleSettings,
            appRules = listOf(
                AppRoutingRule("ru.bank.app", "Bank", AppRoutingMode.DIRECT),
                AppRoutingRule("org.telegram.messenger", "Telegram", AppRoutingMode.VPN),
            ),
            domainRules = listOf(
                DomainRoutingRule("youtube.com", DomainRoutingMode.VPN),
                DomainRoutingRule("bank.example", DomainRoutingMode.DIRECT),
            ),
            ownPackageName = "com.layer.app",
        ).json
        val root = Json.parseToJsonElement(json).jsonObject
        val dnsRules = root["dns"]!!.jsonObject["rules"]!!.jsonArray
        val vpnDns = dnsRules.first { rule ->
            rule.jsonObject["domain_suffix"]?.toString().orEmpty().contains("youtube.com")
        }.jsonObject
        assertEquals("dns-proxy", vpnDns["server"]!!.jsonPrimitive.content)
        val autoDns = dnsRules.first { it.jsonObject.containsKey("rule_set") }.jsonObject
        assertEquals("dns-proxy", autoDns["server"]!!.jsonPrimitive.content)
        val directDns = dnsRules.first { rule ->
            rule.jsonObject["domain_suffix"]?.toString().orEmpty().contains("bank.example")
        }.jsonObject
        assertEquals("dns-direct", directDns["server"]!!.jsonPrimitive.content)
        val directAppDns = dnsRules.first { rule ->
            rule.jsonObject["package_name"]?.toString().orEmpty().contains("ru.bank.app")
        }.jsonObject
        assertEquals("dns-direct", directAppDns["server"]!!.jsonPrimitive.content)
        val vpnAppDns = dnsRules.first { rule ->
            rule.jsonObject["package_name"]?.toString().orEmpty().contains("org.telegram.messenger")
        }.jsonObject
        assertEquals("dns-proxy", vpnAppDns["server"]!!.jsonPrimitive.content)
        assertEquals("dns-direct", root["dns"]!!.jsonObject["final"]!!.jsonPrimitive.content)
        val dnsProxy = root["dns"]!!.jsonObject["servers"]!!.jsonArray
            .first { it.jsonObject["tag"]!!.jsonPrimitive.content == "dns-proxy" }
            .jsonObject
        assertEquals("udp", dnsProxy["type"]!!.jsonPrimitive.content)
        assertFalse(
            root["dns"]!!.jsonObject["servers"]!!.jsonArray.any { server ->
                server.jsonObject["type"]?.jsonPrimitive?.content == "https"
            },
        )
    }

    @Test
    fun geminiAlwaysUsesDirectTrafficAndDedicatedPrivateDns() {
        val json = SingBoxConfigGenerator.generate(
            uuid = sampleUuid,
            settings = sampleSettings,
            appRules = listOf(
                AppRoutingRule(
                    FixedAppRoutingPolicy.GEMINI_PACKAGE,
                    "Gemini",
                    AppRoutingMode.VPN,
                ),
            ),
            domainRules = emptyList(),
            ownPackageName = "com.layer.app",
            excludeDirectFromTun = true,
        ).json
        val root = Json.parseToJsonElement(json).jsonObject
        val tun = root["inbounds"]!!.jsonArray.first().jsonObject
        val excluded = tun["exclude_package"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertFalse(excluded.contains(FixedAppRoutingPolicy.GEMINI_PACKAGE))

        assertTrue(routePackageNames(json, "direct").contains(FixedAppRoutingPolicy.GEMINI_PACKAGE))
        assertFalse(routePackageNames(json, "proxy").contains(FixedAppRoutingPolicy.GEMINI_PACKAGE))

        val dns = root["dns"]!!.jsonObject
        val geminiServer = dns["servers"]!!.jsonArray
            .first { it.jsonObject["tag"]!!.jsonPrimitive.content == FixedAppRoutingPolicy.GEMINI_DNS_TAG }
            .jsonObject
        assertEquals("tls", geminiServer["type"]!!.jsonPrimitive.content)
        assertEquals(FixedAppRoutingPolicy.GEMINI_DNS_HOST, geminiServer["server"]!!.jsonPrimitive.content)
        assertEquals(853, geminiServer["server_port"]!!.jsonPrimitive.content.toInt())
        assertEquals("dns-local", geminiServer["domain_resolver"]!!.jsonPrimitive.content)
        assertFalse(geminiServer.containsKey("detour"))

        val geminiRule = dns["rules"]!!.jsonArray.first { rule ->
            rule.jsonObject["package_name"]?.toString().orEmpty()
                .contains(FixedAppRoutingPolicy.GEMINI_PACKAGE)
        }.jsonObject
        assertEquals(
            FixedAppRoutingPolicy.GEMINI_DNS_TAG,
            geminiRule["server"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun geminiQuicIsRejectedBeforeItsDirectRule() {
        val json = SingBoxConfigGenerator.generate(
            uuid = sampleUuid,
            settings = sampleSettings,
            appRules = emptyList(),
            domainRules = emptyList(),
            ownPackageName = "com.layer.app",
        ).json
        val rules = Json.parseToJsonElement(json).jsonObject["route"]!!.jsonObject["rules"]!!.jsonArray
        fun indexOfGemini(predicate: (JsonObject) -> Boolean): Int = rules.indexOfFirst { rule ->
            val obj = rule.jsonObject
            obj["package_name"]?.toString().orEmpty()
                .contains(FixedAppRoutingPolicy.GEMINI_PACKAGE) && predicate(obj)
        }
        val quicReject = indexOfGemini { obj ->
            obj["protocol"]?.jsonPrimitive?.content == "quic" &&
                obj["action"]?.jsonPrimitive?.content == "reject"
        }
        val direct = indexOfGemini { obj ->
            obj["outbound"]?.jsonPrimitive?.content == "direct"
        }
        assertTrue(quicReject >= 0)
        assertTrue(direct >= 0)
        assertTrue(quicReject < direct)
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

    @Test
    fun tunExcludePackageBypassesDirectAppsWhenAllowed() {
        val json = SingBoxConfigGenerator.generate(
            uuid = sampleUuid,
            settings = sampleSettings,
            appRules = listOf(
                AppRoutingRule("ru.bank.app", "Банк", AppRoutingMode.DIRECT),
                AppRoutingRule("com.google.android.youtube", "YouTube", AppRoutingMode.VPN),
                AppRoutingRule("com.android.chrome", "Chrome", AppRoutingMode.SMART),
                AppRoutingRule("org.telegram.messenger", "Telegram", AppRoutingMode.VPN),
            ),
            domainRules = emptyList(),
            ownPackageName = "com.layer.app",
            excludeDirectFromTun = true,
        ).json
        val tun = Json.parseToJsonElement(json).jsonObject["inbounds"]!!.jsonArray.first().jsonObject
        val excluded = tun["exclude_package"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(
            listOf(
                "com.google.android.gms",
                "com.google.android.gsf",
                "ru.bank.app",
            ),
            excluded,
        )
        assertFalse(excluded.contains("com.google.android.youtube"))
        assertFalse(excluded.contains("org.telegram.messenger"))
        assertFalse(excluded.contains("com.android.chrome"))
        assertFalse(excluded.contains("com.layer.app"))
        assertFalse(tun.containsKey("include_package"))
        assertTrue(json.contains("ru.bank.app"))
        assertTrue(json.contains("com.google.android.gms"))
    }

    @Test
    fun tunExcludePackageOmittedUnderLockdown() {
        val json = SingBoxConfigGenerator.generate(
            uuid = sampleUuid,
            settings = sampleSettings,
            appRules = listOf(
                AppRoutingRule("ru.bank.app", "Банк", AppRoutingMode.DIRECT),
            ),
            domainRules = emptyList(),
            ownPackageName = "com.layer.app",
            excludeDirectFromTun = false,
        ).json
        val tun = Json.parseToJsonElement(json).jsonObject["inbounds"]!!.jsonArray.first().jsonObject
        assertFalse(tun.containsKey("exclude_package"))
        assertTrue(json.contains("ru.bank.app"))
        assertTrue(json.contains("com.google.android.gms"))
    }

    @Test
    fun tunExcludeSkipsDirectAppThatSharesUidWithSmartBrowser() {
        val json = SingBoxConfigGenerator.generate(
            uuid = sampleUuid,
            settings = sampleSettings,
            appRules = listOf(
                AppRoutingRule("ru.bank.app", "Банк", AppRoutingMode.DIRECT),
            ),
            domainRules = emptyList(),
            ownPackageName = "com.layer.app",
            excludeDirectFromTun = true,
            packagesSharingUid = { pkg ->
                when (pkg) {
                    "ru.bank.app", "com.android.chrome" ->
                        listOf("ru.bank.app", "com.android.chrome")
                    else -> listOf(pkg)
                }
            },
        ).json
        val tun = Json.parseToJsonElement(json).jsonObject["inbounds"]!!.jsonArray.first().jsonObject
        val excluded = tun["exclude_package"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(
            listOf("com.google.android.gms", "com.google.android.gsf"),
            excluded,
        )
        assertFalse(excluded.contains("ru.bank.app"))
        assertFalse(excluded.contains("com.android.chrome"))
        assertFalse(routePackageNames(json, "direct").contains("ru.bank.app"))
        val automaticProxy = Json.parseToJsonElement(json).jsonObject["route"]!!.jsonObject["rules"]!!.jsonArray
            .indexOfFirst { rule ->
                val obj = rule.jsonObject
                obj["outbound"]?.jsonPrimitive?.content == "proxy" &&
                    obj["rule_set"]?.toString().orEmpty().contains("rs-youtube")
            }
        assertTrue(automaticProxy >= 0)
    }

    @Test
    fun directAppWithUnresolvedUidIsNeitherExcludedNorRoutedDirect() {
        val json = SingBoxConfigGenerator.generate(
            uuid = sampleUuid,
            settings = sampleSettings,
            appRules = listOf(
                AppRoutingRule("ru.missing.app", "Пропал", AppRoutingMode.DIRECT),
            ),
            domainRules = emptyList(),
            ownPackageName = "com.layer.app",
            excludeDirectFromTun = true,
            packagesSharingUid = { pkg -> if (pkg == "ru.missing.app") null else listOf(pkg) },
        ).json
        val tun = Json.parseToJsonElement(json).jsonObject["inbounds"]!!.jsonArray.first().jsonObject
        val excluded = tun["exclude_package"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertFalse(excluded.contains("ru.missing.app"))
        assertFalse(routePackageNames(json, "direct").contains("ru.missing.app"))
    }

    @Test
    fun sharedUidDirectPlusVpnKeepsVpnRuleAndDropsAppDirect() {
        val json = SingBoxConfigGenerator.generate(
            uuid = sampleUuid,
            settings = sampleSettings,
            appRules = listOf(
                AppRoutingRule("com.termux", "Termux", AppRoutingMode.DIRECT),
                AppRoutingRule("com.termux.styling", "Termux styling", AppRoutingMode.VPN),
            ),
            domainRules = emptyList(),
            ownPackageName = "com.layer.app",
            excludeDirectFromTun = true,
            packagesSharingUid = { pkg ->
                when (pkg) {
                    "com.termux", "com.termux.styling" ->
                        listOf("com.termux", "com.termux.styling")
                    else -> listOf(pkg)
                }
            },
        ).json
        val tun = Json.parseToJsonElement(json).jsonObject["inbounds"]!!.jsonArray.first().jsonObject
        val excluded = tun["exclude_package"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertFalse(excluded.contains("com.termux"))
        assertFalse(excluded.contains("com.termux.styling"))
        assertFalse(routePackageNames(json, "direct").contains("com.termux"))
        assertTrue(routePackageNames(json, "proxy").contains("com.termux.styling"))
    }

    @Test
    fun sharedUidOfDirectPackagesStaysDirectAndCanBeExcluded() {
        val json = SingBoxConfigGenerator.generate(
            uuid = sampleUuid,
            settings = sampleSettings,
            appRules = listOf(
                AppRoutingRule("com.bank.main", "Bank", AppRoutingMode.DIRECT),
                AppRoutingRule("com.bank.plugin", "Bank plugin", AppRoutingMode.DIRECT),
            ),
            domainRules = emptyList(),
            ownPackageName = "com.layer.app",
            excludeDirectFromTun = true,
            packagesSharingUid = { pkg ->
                when (pkg) {
                    "com.bank.main", "com.bank.plugin" ->
                        listOf("com.bank.main", "com.bank.plugin")
                    else -> listOf(pkg)
                }
            },
        ).json
        val tun = Json.parseToJsonElement(json).jsonObject["inbounds"]!!.jsonArray.first().jsonObject
        val excluded = tun["exclude_package"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(excluded.contains("com.bank.main"))
        assertTrue(excluded.contains("com.bank.plugin"))
        val direct = routePackageNames(json, "direct")
        assertTrue(direct.contains("com.bank.main"))
        assertTrue(direct.contains("com.bank.plugin"))
    }

    @Test
    fun unlistedBrowserStaysInTunAndAutomaticDomainsStillProxy() {
        val json = SingBoxConfigGenerator.generate(
            uuid = sampleUuid,
            settings = sampleSettings,
            appRules = listOf(
                AppRoutingRule("ru.bank.app", "Банк", AppRoutingMode.DIRECT),
            ),
            domainRules = emptyList(),
            ownPackageName = "com.layer.app",
            excludeDirectFromTun = true,
        ).json
        val root = Json.parseToJsonElement(json).jsonObject
        val tun = root["inbounds"]!!.jsonArray.first().jsonObject
        val excluded = tun["exclude_package"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(excluded.contains("ru.bank.app"))
        assertFalse(excluded.contains("org.mozilla.firefox"))
        assertFalse(excluded.contains("com.android.chrome"))
        assertFalse(tun.containsKey("include_package"))

        val routeRules = root["route"]!!.jsonObject["rules"]!!.jsonArray
        val automaticProxy = routeRules.indexOfFirst { rule ->
            val obj = rule.jsonObject
            obj["outbound"]?.jsonPrimitive?.content == "proxy" &&
                obj["rule_set"]?.toString().orEmpty().contains("rs-youtube")
        }
        assertTrue(automaticProxy >= 0)
        val appDirect = routeRules.indexOfFirst { rule ->
            val obj = rule.jsonObject
            obj["outbound"]?.jsonPrimitive?.content == "direct" &&
                obj["package_name"]?.toString().orEmpty().contains("ru.bank.app")
        }
        assertTrue(appDirect >= 0)
        assertTrue(automaticProxy > appDirect)
        assertEquals("direct", root["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
    }

    private fun routePackageNames(json: String, outbound: String): List<String> {
        val rules = Json.parseToJsonElement(json).jsonObject["route"]!!.jsonObject["rules"]!!.jsonArray
        return rules.flatMap { rule ->
            val obj = rule.jsonObject
            if (obj["outbound"]?.jsonPrimitive?.content != outbound) {
                emptyList()
            } else {
                obj["package_name"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            }
        }
    }
}
