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
import com.layer.core.singbox.CacheFileOptions
import com.layer.core.singbox.DirectOutbound
import com.layer.core.singbox.DnsOptions
import com.layer.core.singbox.DnsRule
import com.layer.core.singbox.ExperimentalOptions
import com.layer.core.singbox.GrpcTransport
import com.layer.core.singbox.HttpClient
import com.layer.core.singbox.HttpTransport
import com.layer.core.singbox.HttpUpgradeTransport
import com.layer.core.singbox.HttpsDnsServer
import com.layer.core.singbox.LocalDnsServer
import com.layer.core.singbox.LogOptions
import com.layer.core.singbox.OutboundTls
import com.layer.core.singbox.RealityOptions
import com.layer.core.singbox.RouteOptions
import com.layer.core.singbox.RouteRule
import com.layer.core.singbox.RuleSet
import com.layer.core.singbox.SingBoxConfig
import com.layer.core.singbox.TunInbound
import com.layer.core.singbox.UtlsOptions
import com.layer.core.singbox.VlessOutbound
import com.layer.core.singbox.WsTransport
import com.layer.core.singbox.XhttpTransport
import kotlinx.serialization.json.Json

data class ConfigGenerationResult(
    val json: String,
    val error: String? = null,
) {
    val isSuccess: Boolean get() = error == null
}

/**
 * Builds a sing-box config from typed 1.14 option models.
 * Rule order matches [com.layer.core.routing.RoutingPriority].
 *
 * Config is generated for libbox on Android (VpnService TUN). `auto_detect_interface`
 * is omitted: PlatformInterface.protect() prevents routing loops.
 */
object SingBoxConfigGenerator {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = false
        explicitNulls = false
    }

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
        adBlockPackages: List<String> = emptyList(),
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
        val adPackages = adBlockPackages.filter { it.isNotBlank() }.distinct()
        val adBlockPath = adBlockRuleSetPath?.takeIf {
            adPackages.isNotEmpty() && it.isNotBlank()
        }
        val route = buildRoute(
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
            adBlockPackages = if (adBlockPath != null) adPackages else emptyList(),
            adBlockPath = adBlockPath,
        )
        // 1.14 dropped download_detour; remote rule-sets download through
        // a named HTTP client whose detour is the VLESS outbound.
        val wantsRemoteLists = route.ruleSet.any { it.type == "remote" }

        val generated = SingBoxConfig(
            log = LogOptions(level = logLevel, timestamp = true),
            dns = buildDns(
                server = server,
                directDomains = directDomains,
                vpnDomains = vpnDomains,
                automaticTags = automaticTags,
                ipv6Enabled = settings.ipv6Enabled,
                adBlockPackages = if (adBlockPath != null) adPackages else emptyList(),
            ),
            inbounds = listOf(buildTun(settings.ipv6Enabled)),
            outbounds = listOf(
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
                DirectOutbound(tag = "direct"),
            ),
            route = route,
            experimental = ExperimentalOptions(
                cacheFile = CacheFileOptions(
                    enabled = true,
                    path = "cache.db",
                ),
            ),
            httpClients = if (wantsRemoteLists) {
                listOf(HttpClient(tag = PROXY_HTTP_CLIENT, detour = "proxy"))
            } else {
                null
            },
        )

        return ConfigGenerationResult(json.encodeToString(SingBoxConfig.serializer(), generated))
    }

    private fun buildDns(
        server: String,
        directDomains: List<String>,
        vpnDomains: List<String>,
        automaticTags: List<String>,
        ipv6Enabled: Boolean,
        adBlockPackages: List<String>,
    ): DnsOptions {
        val rules = buildList {
            add(
                DnsRule(
                    domain = listOf(server),
                    action = "route",
                    server = "dns-local",
                ),
            )
            if (directDomains.isNotEmpty()) {
                add(
                    DnsRule(
                        domainSuffix = directDomains,
                        action = "route",
                        server = "dns-direct",
                    ),
                )
            }
            if (adBlockPackages.isNotEmpty()) {
                add(
                    DnsRule(
                        packageName = adBlockPackages,
                        ruleSet = listOf(AdBlockPolicy.TAG),
                        action = "reject",
                    ),
                )
            }
            if (vpnDomains.isNotEmpty()) {
                add(
                    DnsRule(
                        domainSuffix = vpnDomains,
                        action = "route",
                        server = "dns-direct",
                    ),
                )
            }
            if (automaticTags.isNotEmpty()) {
                add(
                    DnsRule(
                        ruleSet = automaticTags,
                        action = "route",
                        server = "dns-direct",
                    ),
                )
            }
        }
        return DnsOptions(
            servers = listOf(
                LocalDnsServer(tag = "dns-local"),
                HttpsDnsServer(tag = "dns-direct", server = "8.8.8.8"),
            ),
            rules = rules,
            final = "dns-direct",
            strategy = if (ipv6Enabled) "prefer_ipv4" else "ipv4_only",
            reverseMapping = true,
        )
    }

    private fun buildTun(ipv6: Boolean): TunInbound {
        return TunInbound(
            tag = "tun-in",
            address = buildList {
                add("172.19.0.1/30")
                if (ipv6) add("fdfe:dcba:9876::1/126")
            },
            mtu = 1500,
            autoRoute = true,
            strictRoute = true,
            // mixed/system TCP NATs SYNs onto a kernel listener bound to the TUN
            // address. Layer excludes itself from VpnService, so that listener
            // never accepts and user TCP dies after pre-match. gVisor keeps L3→L4
            // in-process, which is the working model on Android VpnService.
            stack = "gvisor",
            routeAddress = buildList {
                add("0.0.0.0/0")
                if (ipv6) add("::/0")
            },
            // Sniffed QUIC otherwise expires in 30s; the next datagram is a new
            // connection without ClientHello and falls through to DIRECT.
            udpTimeout = "5m",
        )
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
    ): VlessOutbound {
        val includeAlpn = !config.isReality &&
            !VlessTransport.isXhttp(config.network) &&
            alpn.isNotBlank()
        return VlessOutbound(
            tag = "proxy",
            server = server,
            serverPort = port,
            uuid = uuid,
            flow = flow.takeIf { it.isNotBlank() },
            packetEncoding = "xudp",
            domainResolver = "dns-local",
            // After Doze/screen-off, carrier NAT drops idle TCP. Default keep-alive
            // idle is 5m, so the VLESS socket looks alive until the next dial times out.
            tcpKeepAlive = "15s",
            tcpKeepAliveInterval = "15s",
            connectTimeout = "15s",
            tls = OutboundTls(
                enabled = true,
                serverName = sni,
                alpn = if (includeAlpn) listOf(alpn) else null,
                utls = UtlsOptions(enabled = true, fingerprint = fingerprint),
                reality = if (config.isReality) {
                    RealityOptions(
                        enabled = true,
                        publicKey = RealityPublicKey.forSingBox(config.publicKey),
                        shortId = config.shortId.trim(),
                    )
                } else {
                    null
                },
            ),
            transport = buildTransport(config),
        )
    }

    private fun buildTransport(config: VlessServerConfig): com.layer.core.singbox.VlessTransport? {
        val network = VlessTransport.normalize(config.network)
        val path = config.path.ifBlank { "/" }
        val host = config.httpHost.ifBlank { config.serverName }
        return when (network) {
            VlessTransport.TCP -> null
            VlessTransport.WS -> WsTransport(
                path = path,
                headers = host.takeIf { it.isNotBlank() }?.let { mapOf("Host" to it) },
            )
            VlessTransport.HTTPUPGRADE -> HttpUpgradeTransport(
                host = host.takeIf { it.isNotBlank() },
                path = path,
            )
            VlessTransport.HTTP -> HttpTransport(
                host = host.takeIf { it.isNotBlank() }?.let { listOf(it) },
                path = path,
            )
            VlessTransport.GRPC -> GrpcTransport(
                serviceName = config.path.trim('/').ifBlank { "TunService" },
            )
            VlessTransport.XHTTP -> XhttpTransport(
                host = host.takeIf { it.isNotBlank() },
                path = path,
                mode = config.transportMode.ifBlank { "auto" },
                xPaddingBytes = config.xPaddingBytes.takeIf { it.isNotBlank() },
            )
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
        adBlockPackages: List<String>,
        adBlockPath: String?,
    ): RouteOptions {
        val ruleSets = buildList {
            RuleSetCatalog.vpnLists.filter { it.tag in automaticTags }.forEach { set ->
                val localPath = localRuleSets[set.tag]
                add(
                    if (!localPath.isNullOrBlank()) {
                        RuleSet(
                            tag = set.tag,
                            format = "binary",
                            type = "local",
                            path = localPath,
                        )
                    } else if (remoteRuleSetFallback) {
                        RuleSet(
                            tag = set.tag,
                            format = "binary",
                            type = "remote",
                            url = set.url,
                            updateInterval = RuleSetCatalog.UPDATE_INTERVAL,
                        )
                    } else {
                        return@forEach
                    },
                )
            }
            if (!adBlockPath.isNullOrBlank()) {
                add(
                    RuleSet(
                        tag = AdBlockPolicy.TAG,
                        type = "local",
                        format = "source",
                        path = adBlockPath,
                    ),
                )
            }
        }
        val rules = buildList {
            add(RouteRule(action = "sniff"))
            add(RouteRule(protocol = "dns", action = "hijack-dns"))
            add(RouteRule(port = 53, action = "hijack-dns"))
            add(
                RouteRule(
                    ipCidr = listOf(
                        "127.0.0.0/8",
                        "::1/128",
                        // Clash/Happ fake-ip leftover in app DNS caches.
                        "198.18.0.0/15",
                    ),
                    action = "reject",
                ),
            )
            // YouTube/Google DoH on :443 bypasses hijack-dns. Routed through
            // VLESS+Vision it opens a second TLS to the VPS and cancels
            // in-flight Telegram dials ("operation was canceled").
            add(
                RouteRule(
                    ipCidr = PUBLIC_DOH_IPV4,
                    port = 443,
                    action = "reject",
                ),
            )
            add(RouteRule(ipIsPrivate = true, outbound = "direct"))
            add(RouteRule(domain = listOf(server), outbound = "direct"))
            if (!resolvedServerIp.isNullOrBlank()) {
                add(RouteRule(ipCidr = listOf("$resolvedServerIp/32"), outbound = "direct"))
            }
            if (ownPackageName.isNotBlank()) {
                add(RouteRule(packageName = listOf(ownPackageName), outbound = "direct"))
            }
            add(RouteRule(packageName = PushDirectPackages.packages, outbound = "direct"))
            // 1. App DIRECT
            if (directApps.isNotEmpty()) {
                add(RouteRule(packageName = directApps, outbound = "direct"))
            }
            // 2. App VPN
            if (vpnApps.isNotEmpty()) {
                add(RouteRule(packageName = vpnApps, outbound = "proxy", udpTimeout = "5m"))
            }
            // 3. User domain DIRECT
            if (directDomains.isNotEmpty()) {
                add(RouteRule(domainSuffix = directDomains, outbound = "direct"))
            }
            // 4. User domain VPN
            if (vpnDomains.isNotEmpty()) {
                add(
                    RouteRule(
                        protocol = "quic",
                        domainSuffix = vpnDomains,
                        action = "reject",
                    ),
                )
                add(
                    RouteRule(
                        domainSuffix = vpnDomains,
                        outbound = "proxy",
                        udpTimeout = "5m",
                    ),
                )
            }
            // 5. DNS ad hostlist, only for apps the user added.
            // package_name is checked before the rule-set, so other apps
            // never walk the list. User domain DIRECT still wins above.
            if (adBlockPackages.isNotEmpty() && !adBlockPath.isNullOrBlank()) {
                add(
                    RouteRule(
                        packageName = adBlockPackages,
                        ruleSet = listOf(AdBlockPolicy.TAG),
                        action = "reject",
                    ),
                )
            }
            // 6. Automatic rule-set
            if (automaticTags.isNotEmpty()) {
                // VLESS+Vision is TCP; QUIC/HTTP3 over xudp stalls (YouTube Music
                // keeps the session open with no download while Telegram TCP works).
                // Rejecting QUIC makes the app fall back to TCP through the same lists.
                add(
                    RouteRule(
                        protocol = "quic",
                        ruleSet = automaticTags,
                        action = "reject",
                    ),
                )
                add(
                    RouteRule(
                        ruleSet = automaticTags,
                        outbound = "proxy",
                        udpTimeout = "5m",
                    ),
                )
            }
        }
        val wantsRemoteLists = ruleSets.any { it.type == "remote" }
        return RouteOptions(
            defaultDomainResolver = "dns-local",
            defaultHttpClient = if (wantsRemoteLists) PROXY_HTTP_CLIENT else null,
            ruleSet = ruleSets,
            rules = rules,
            final = "direct",
        )
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

    private const val PROXY_HTTP_CLIENT = "proxy-http"
}
