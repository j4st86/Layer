package com.layer.core.config

import com.layer.core.i18n.copy
import com.layer.core.model.AppRoutingMode
import com.layer.core.model.AppRoutingRule
import com.layer.core.model.DEFAULT_TLS_FINGERPRINT
import com.layer.core.model.DEFAULT_VLESS_FLOW
import com.layer.core.model.DEFAULT_VLESS_PORT
import com.layer.core.model.DomainRoutingMode
import com.layer.core.model.DomainRoutingRule
import com.layer.core.model.LayerSettings
import com.layer.core.model.VlessServerConfig
import com.layer.core.routing.HostnameNormalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class ConfigGenerationResult(
    val json: String,
    val error: String? = null,
) {
    val isSuccess: Boolean get() = error == null
}

/**
 * Builds a sing-box JSON config. Rule order matches [com.layer.core.routing.RoutingPriority].
 *
 * Config is generated for libbox on Android (VpnService TUN). `auto_detect_interface`
 * is omitted: PlatformInterface.protect() prevents routing loops.
 */
object SingBoxConfigGenerator {
    private val prettyJson = Json { prettyPrint = true }

    // App DoH endpoints that otherwise race Telegram for the Vision TCP.
    private val PUBLIC_DOH_IPV4 = listOf(
        "1.1.1.1/32",
        "1.0.0.1/32",
        "8.8.8.8/32",
        "8.8.4.4/32",
    )

    fun generate(
        uuid: String,
        settings: LayerSettings,
        appRules: List<AppRoutingRule>,
        domainRules: List<DomainRoutingRule>,
        ownPackageName: String,
        resolvedServerIp: String? = null,
        localRuleSets: Map<String, String> = emptyMap(),
        remoteRuleSetFallback: Boolean = true,
        adBlockRuleSetPath: String? = null,
        logLevel: String = "info",
    ): ConfigGenerationResult {
        val trimmedUuid = VlessLinkParser.extractUuid(uuid)
        if (trimmedUuid.isNullOrBlank()) {
            return ConfigGenerationResult(
                json = "",
                error = copy("Add a server via QR or link.", "Добавьте сервер по QR или ссылке."),
            )
        }

        val config = settings.server
        val server = config.address.trim()
        if (server.isBlank()) {
            return ConfigGenerationResult(
                json = "",
                error = copy("Add a server via QR or link.", "Добавьте сервер по QR или ссылке."),
            )
        }
        val port = config.port.takeIf { it in 1..65535 } ?: DEFAULT_VLESS_PORT
        val network = VlessTransport.normalize(config.network)
        if (!VlessTransport.isSupported(network)) {
            return ConfigGenerationResult(
                json = "",
                error = copy(
                    "Transport $network is not supported in this build.",
                    "Транспорт $network в этой сборке не поддерживается.",
                ),
            )
        }
        val flow = VlessTransport.effectiveFlow(network, config.flow.ifBlank {
            if (VlessTransport.isTcp(network)) DEFAULT_VLESS_FLOW else ""
        })
        val sni = config.serverName.ifBlank { server }
        val fingerprint = config.fingerprint.ifBlank {
            if (config.isReality) "chrome" else DEFAULT_TLS_FINGERPRINT
        }
        val alpn = config.alpn.ifBlank { "http/1.1" }
        val dialAddress = resolvedServerIp?.takeIf { it.isNotBlank() } ?: server

        val directApps = appRules.filter { it.mode == AppRoutingMode.DIRECT }.map { it.packageName }
        val vpnApps = appRules.filter { it.mode == AppRoutingMode.VPN }.map { it.packageName }
        val directDomains = domainRules
            .filter { it.mode == DomainRoutingMode.DIRECT }
            .map { HostnameNormalizer.normalize(it.domain).domain }
            .filter { it.isNotBlank() }
        val vpnDomains = domainRules
            .filter { it.mode == DomainRoutingMode.VPN }
            .map { HostnameNormalizer.normalize(it.domain).domain }
            .filter { it.isNotBlank() }
        val automaticTags = automaticRuleSetTags(
            enabled = settings.automaticRuleSetEnabled,
            localRuleSets = localRuleSets,
            remoteFallback = remoteRuleSetFallback,
        )
        val adBlockPath = adBlockRuleSetPath?.takeIf {
            settings.adBlockEnabled && it.isNotBlank()
        }

        val generated = buildJsonObject {
            putJsonObject("log") {
                put("level", logLevel)
                put("timestamp", true)
            }
            put(
                "dns",
                buildDns(
                    server = server,
                    directDomains = directDomains,
                    vpnDomains = vpnDomains,
                    automaticTags = automaticTags,
                    ipv6Enabled = settings.ipv6Enabled,
                    adBlockEnabled = adBlockPath != null,
                ),
            )
            putJsonArray("inbounds") {
                add(buildTun(settings.ipv6Enabled))
            }
            putJsonArray("outbounds") {
                add(
                    buildVless(
                        server = dialAddress,
                        port = port,
                        uuid = trimmedUuid,
                        flow = flow,
                        sni = sni,
                        fingerprint = fingerprint,
                        alpn = alpn,
                        config = config.copy(network = network, flow = flow),
                    ),
                )
                add(buildJsonObject {
                    put("type", "direct")
                    put("tag", "direct")
                })
            }
            put(
                "route",
                buildRoute(
                    server = server,
                    resolvedServerIp = resolvedServerIp,
                    ownPackageName = ownPackageName,
                    directApps = directApps,
                    vpnApps = vpnApps,
                    directDomains = directDomains,
                    vpnDomains = vpnDomains,
                    automaticTags = automaticTags,
                    localRuleSets = localRuleSets,
                    remoteRuleSetFallback = remoteRuleSetFallback,
                    adBlockPath = adBlockPath,
                ),
            )
            putJsonObject("experimental") {
                putJsonObject("cache_file") {
                    put("enabled", true)
                    put("path", "cache.db")
                    put("store_rdrc", true)
                }
            }
        }

        return ConfigGenerationResult(prettyJson.encodeToString(JsonObject.serializer(), generated))
    }

    private fun buildDns(
        server: String,
        directDomains: List<String>,
        vpnDomains: List<String>,
        automaticTags: List<String>,
        ipv6Enabled: Boolean,
        adBlockEnabled: Boolean,
    ): JsonObject = buildJsonObject {
        putJsonArray("servers") {
            add(buildJsonObject {
                put("type", "local")
                put("tag", "dns-local")
            })
            add(buildJsonObject {
                put("type", "https")
                put("tag", "dns-direct")
                put("server", "8.8.8.8")
            })
        }
        putJsonArray("rules") {
            add(buildJsonObject {
                putJsonArray("domain") { add(server) }
                put("action", "route")
                put("server", "dns-local")
            })
            if (directDomains.isNotEmpty()) {
                add(buildJsonObject {
                    putJsonArray("domain_suffix") { directDomains.forEach { add(it) } }
                    put("action", "route")
                    put("server", "dns-direct")
                })
            }
            if (adBlockEnabled) {
                add(buildJsonObject {
                    putJsonArray("rule_set") { add(AdBlockPolicy.TAG) }
                    put("action", "reject")
                })
            }
            if (vpnDomains.isNotEmpty()) {
                add(buildJsonObject {
                    putJsonArray("domain_suffix") { vpnDomains.forEach { add(it) } }
                    put("action", "route")
                    put("server", "dns-direct")
                })
            }
            if (automaticTags.isNotEmpty()) {
                add(buildJsonObject {
                    putJsonArray("rule_set") { automaticTags.forEach { add(it) } }
                    put("action", "route")
                    put("server", "dns-direct")
                })
            }
        }
        put("final", "dns-direct")
        put("strategy", if (ipv6Enabled) "prefer_ipv4" else "ipv4_only")
        put("independent_cache", true)
        put("reverse_mapping", true)
    }

    private fun buildTun(ipv6: Boolean): JsonObject = buildJsonObject {
        put("type", "tun")
        put("tag", "tun-in")
        putJsonArray("address") {
            add("172.19.0.1/30")
            if (ipv6) add("fdfe:dcba:9876::1/126")
        }
        put("mtu", 1500)
        put("auto_route", true)
        put("strict_route", true)
        // mixed/system TCP NATs SYNs onto a kernel listener bound to the TUN
        // address. Layer excludes itself from VpnService, so that listener
        // never accepts and user TCP dies after pre-match. gVisor keeps L3→L4
        // in-process, which is the working model on Android VpnService.
        put("stack", "gvisor")
        putJsonArray("route_address") {
            add("0.0.0.0/0")
            if (ipv6) add("::/0")
        }
        // Sniffed QUIC otherwise expires in 30s; the next datagram is a new
        // connection without ClientHello and falls through to DIRECT.
        put("udp_timeout", "5m")
    }

    private fun buildVless(
        server: String,
        port: Int,
        uuid: String,
        flow: String,
        sni: String,
        fingerprint: String,
        alpn: String,
        config: VlessServerConfig,
    ): JsonObject = buildJsonObject {
        put("type", "vless")
        put("tag", "proxy")
        put("server", server)
        put("server_port", port)
        put("uuid", uuid)
        if (flow.isNotBlank()) {
            put("flow", flow)
        }
        put("packet_encoding", "xudp")
        put("domain_resolver", "dns-local")
        // After Doze/screen-off, carrier NAT drops idle TCP. Default keep-alive
        // idle is 5m, so the VLESS socket looks alive until the next dial times out.
        put("tcp_keep_alive", "15s")
        put("tcp_keep_alive_interval", "15s")
        put("connect_timeout", "15s")
        putJsonObject("tls") {
            put("enabled", true)
            put("server_name", sni)
            if (!config.isReality && !VlessTransport.isXhttp(config.network) && alpn.isNotBlank()) {
                putJsonArray("alpn") { add(alpn) }
            }
            putJsonObject("utls") {
                put("enabled", true)
                put("fingerprint", fingerprint)
            }
            if (config.isReality) {
                putJsonObject("reality") {
                    put("enabled", true)
                    put("public_key", RealityPublicKey.forSingBox(config.publicKey))
                    put("short_id", config.shortId.trim())
                }
            }
        }
        buildTransport(config)?.let { put("transport", it) }
    }

    private fun buildTransport(config: VlessServerConfig): JsonObject? {
        val network = VlessTransport.normalize(config.network)
        val path = config.path.ifBlank { "/" }
        val host = config.httpHost.ifBlank { config.serverName }
        return when (network) {
            VlessTransport.TCP -> null
            VlessTransport.WS -> buildJsonObject {
                put("type", "ws")
                put("path", path)
                if (host.isNotBlank()) {
                    putJsonObject("headers") { put("Host", host) }
                }
            }
            VlessTransport.HTTPUPGRADE -> buildJsonObject {
                put("type", "httpupgrade")
                if (host.isNotBlank()) put("host", host)
                put("path", path)
            }
            VlessTransport.HTTP -> buildJsonObject {
                put("type", "http")
                if (host.isNotBlank()) {
                    putJsonArray("host") { add(host) }
                }
                put("path", path)
            }
            VlessTransport.GRPC -> buildJsonObject {
                put("type", "grpc")
                put("service_name", config.path.trim('/').ifBlank { "TunService" })
            }
            VlessTransport.XHTTP -> buildJsonObject {
                put("type", "xhttp")
                if (host.isNotBlank()) put("host", host)
                put("path", path)
                put("mode", config.transportMode.ifBlank { "auto" })
                if (config.xPaddingBytes.isNotBlank()) {
                    put("x_padding_bytes", config.xPaddingBytes)
                }
            }
            else -> null
        }
    }

    private fun buildRoute(
        server: String,
        resolvedServerIp: String?,
        ownPackageName: String,
        directApps: List<String>,
        vpnApps: List<String>,
        directDomains: List<String>,
        vpnDomains: List<String>,
        automaticTags: List<String>,
        localRuleSets: Map<String, String>,
        remoteRuleSetFallback: Boolean,
        adBlockPath: String?,
    ): JsonObject = buildJsonObject {
        put("default_domain_resolver", "dns-local")
        putJsonArray("rule_set") {
            RuleSetCatalog.vpnLists.filter { it.tag in automaticTags }.forEach { set ->
                val localPath = localRuleSets[set.tag]
                add(buildJsonObject {
                    put("tag", set.tag)
                    put("format", "binary")
                    if (!localPath.isNullOrBlank()) {
                        put("type", "local")
                        put("path", localPath)
                    } else if (remoteRuleSetFallback) {
                        put("type", "remote")
                        put("url", set.url)
                        put("download_detour", "proxy")
                        put("update_interval", RuleSetCatalog.UPDATE_INTERVAL)
                    }
                })
            }
            if (!adBlockPath.isNullOrBlank()) {
                add(buildJsonObject {
                    put("tag", AdBlockPolicy.TAG)
                    put("type", "local")
                    put("format", "source")
                    put("path", adBlockPath)
                })
            }
        }
        putJsonArray("rules") {
            add(buildJsonObject {
                put("action", "sniff")
            })
            add(buildJsonObject {
                put("protocol", "dns")
                put("action", "hijack-dns")
            })
            add(buildJsonObject {
                put("port", 53)
                put("action", "hijack-dns")
            })
            add(buildJsonObject {
                putJsonArray("ip_cidr") {
                    add("127.0.0.0/8")
                    add("::1/128")
                    // Clash/Happ fake-ip leftover in app DNS caches.
                    add("198.18.0.0/15")
                }
                put("action", "reject")
            })
            // YouTube/Google DoH on :443 bypasses hijack-dns. Routed through
            // VLESS+Vision it opens a second TLS to the VPS and cancels
            // in-flight Telegram dials ("operation was canceled").
            add(buildJsonObject {
                putJsonArray("ip_cidr") { PUBLIC_DOH_IPV4.forEach { add(it) } }
                put("port", 443)
                put("action", "reject")
            })
            add(buildJsonObject {
                put("ip_is_private", true)
                put("outbound", "direct")
            })
            add(buildJsonObject {
                putJsonArray("domain") { add(server) }
                put("outbound", "direct")
            })
            if (!resolvedServerIp.isNullOrBlank()) {
                add(buildJsonObject {
                    putJsonArray("ip_cidr") { add("$resolvedServerIp/32") }
                    put("outbound", "direct")
                })
            }
            if (ownPackageName.isNotBlank()) {
                add(buildJsonObject {
                    putJsonArray("package_name") { add(ownPackageName) }
                    put("outbound", "direct")
                })
            }
            add(buildJsonObject {
                putJsonArray("package_name") {
                    PushDirectPackages.packages.forEach { add(it) }
                }
                put("outbound", "direct")
            })
            // 1. App DIRECT
            if (directApps.isNotEmpty()) {
                add(buildJsonObject {
                    putJsonArray("package_name") { directApps.forEach { add(it) } }
                    put("outbound", "direct")
                })
            }
            // 2. App VPN
            if (vpnApps.isNotEmpty()) {
                add(buildJsonObject {
                    putJsonArray("package_name") { vpnApps.forEach { add(it) } }
                    put("outbound", "proxy")
                    put("udp_timeout", "5m")
                })
            }
            // 3. User domain DIRECT
            if (directDomains.isNotEmpty()) {
                add(buildJsonObject {
                    putJsonArray("domain_suffix") { directDomains.forEach { add(it) } }
                    put("outbound", "direct")
                })
            }
            // 4. User domain VPN
            if (vpnDomains.isNotEmpty()) {
                add(buildJsonObject {
                    put("protocol", "quic")
                    putJsonArray("domain_suffix") { vpnDomains.forEach { add(it) } }
                    put("action", "reject")
                })
                add(buildJsonObject {
                    putJsonArray("domain_suffix") { vpnDomains.forEach { add(it) } }
                    put("outbound", "proxy")
                    put("udp_timeout", "5m")
                })
            }
            // 5. DNS ad hostlist (user domain DIRECT still wins as a whitelist)
            if (!adBlockPath.isNullOrBlank()) {
                add(buildJsonObject {
                    putJsonArray("rule_set") { add(AdBlockPolicy.TAG) }
                    put("action", "reject")
                })
            }
            // 6. Automatic rule-set
            if (automaticTags.isNotEmpty()) {
                // VLESS+Vision is TCP; QUIC/HTTP3 over xudp stalls (YouTube Music
                // keeps the session open with no download while Telegram TCP works).
                // Rejecting QUIC makes the app fall back to TCP through the same lists.
                add(buildJsonObject {
                    put("protocol", "quic")
                    putJsonArray("rule_set") { automaticTags.forEach { add(it) } }
                    put("action", "reject")
                })
                add(buildJsonObject {
                    putJsonArray("rule_set") { automaticTags.forEach { add(it) } }
                    put("outbound", "proxy")
                    put("udp_timeout", "5m")
                })
            }
        }
        put("final", "direct")
    }

    private fun automaticRuleSetTags(
        enabled: Boolean,
        localRuleSets: Map<String, String>,
        remoteFallback: Boolean,
    ): List<String> {
        if (!enabled) return emptyList()
        return RuleSetCatalog.vpnLists.map { it.tag }.filter { tag ->
            !localRuleSets[tag].isNullOrBlank() || remoteFallback
        }
    }
}
