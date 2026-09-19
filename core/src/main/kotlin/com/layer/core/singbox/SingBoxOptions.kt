@file:OptIn(ExperimentalSerializationApi::class)

package com.layer.core.singbox

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Typed sing-box 1.14 options that Layer actually emits. Policies still
 * return domain decisions; [com.layer.core.config.SingBoxConfigGenerator]
 * assembles these models instead of concatenating JSON by hand.
 *
 * XHTTP is a Layer patch, so it is modeled here even though it is absent
 * from the stock 1.14.1 schema.
 */
@Serializable
data class SingBoxConfig(
    val log: LogOptions,
    val dns: DnsOptions,
    val inbounds: List<Inbound>,
    val outbounds: List<Outbound>,
    val route: RouteOptions,
    val experimental: ExperimentalOptions,
    @SerialName("http_clients") val httpClients: List<HttpClient>? = null,
)

@Serializable
data class LogOptions(
    val level: String,
    val timestamp: Boolean,
)

@Serializable
data class DnsOptions(
    val servers: List<DnsServer>,
    val rules: List<DnsRule>,
    val `final`: String,
    val strategy: String,
    @SerialName("reverse_mapping") val reverseMapping: Boolean,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface DnsServer {
    val tag: String
}

@Serializable
@SerialName("local")
data class LocalDnsServer(
    override val tag: String,
) : DnsServer

@Serializable
@SerialName("https")
data class HttpsDnsServer(
    override val tag: String,
    val server: String,
) : DnsServer

@Serializable
data class DnsRule(
    val domain: List<String>? = null,
    @SerialName("domain_suffix") val domainSuffix: List<String>? = null,
    @SerialName("rule_set") val ruleSet: List<String>? = null,
    val action: String,
    val server: String? = null,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface Inbound {
    val tag: String
}

@Serializable
@SerialName("tun")
data class TunInbound(
    override val tag: String,
    val address: List<String>,
    val mtu: Int,
    @SerialName("auto_route") val autoRoute: Boolean,
    @SerialName("strict_route") val strictRoute: Boolean,
    val stack: String,
    @SerialName("route_address") val routeAddress: List<String>,
    @SerialName("udp_timeout") val udpTimeout: String,
) : Inbound

@Serializable
@JsonClassDiscriminator("type")
sealed interface Outbound {
    val tag: String
}

@Serializable
@SerialName("direct")
data class DirectOutbound(
    override val tag: String,
) : Outbound

@Serializable
@SerialName("vless")
data class VlessOutbound(
    override val tag: String,
    val server: String,
    @SerialName("server_port") val serverPort: Int,
    val uuid: String,
    val flow: String? = null,
    @SerialName("packet_encoding") val packetEncoding: String,
    @SerialName("domain_resolver") val domainResolver: String,
    @SerialName("tcp_keep_alive") val tcpKeepAlive: String,
    @SerialName("tcp_keep_alive_interval") val tcpKeepAliveInterval: String,
    @SerialName("connect_timeout") val connectTimeout: String,
    val tls: OutboundTls,
    val transport: VlessTransport? = null,
) : Outbound

@Serializable
data class OutboundTls(
    val enabled: Boolean,
    @SerialName("server_name") val serverName: String,
    val alpn: List<String>? = null,
    val utls: UtlsOptions,
    val reality: RealityOptions? = null,
)

@Serializable
data class UtlsOptions(
    val enabled: Boolean,
    val fingerprint: String,
)

@Serializable
data class RealityOptions(
    val enabled: Boolean,
    @SerialName("public_key") val publicKey: String,
    @SerialName("short_id") val shortId: String,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface VlessTransport

@Serializable
@SerialName("ws")
data class WsTransport(
    val path: String,
    val headers: Map<String, String>? = null,
) : VlessTransport

@Serializable
@SerialName("httpupgrade")
data class HttpUpgradeTransport(
    val host: String? = null,
    val path: String,
) : VlessTransport

@Serializable
@SerialName("http")
data class HttpTransport(
    val host: List<String>? = null,
    val path: String,
) : VlessTransport

@Serializable
@SerialName("grpc")
data class GrpcTransport(
    @SerialName("service_name") val serviceName: String,
) : VlessTransport

@Serializable
@SerialName("xhttp")
data class XhttpTransport(
    val host: String? = null,
    val path: String,
    val mode: String,
    @SerialName("x_padding_bytes") val xPaddingBytes: String? = null,
) : VlessTransport

@Serializable
data class RouteOptions(
    @SerialName("default_domain_resolver") val defaultDomainResolver: String,
    @SerialName("default_http_client") val defaultHttpClient: String? = null,
    @EncodeDefault
    @SerialName("rule_set")
    val ruleSet: List<RuleSet> = emptyList(),
    val rules: List<RouteRule>,
    val `final`: String,
)

@Serializable
data class RuleSet(
    val tag: String,
    val format: String,
    val type: String,
    val path: String? = null,
    val url: String? = null,
    @SerialName("update_interval") val updateInterval: String? = null,
)

@Serializable
data class RouteRule(
    val action: String? = null,
    val protocol: String? = null,
    val port: Int? = null,
    @SerialName("ip_cidr") val ipCidr: List<String>? = null,
    @SerialName("ip_is_private") val ipIsPrivate: Boolean? = null,
    val outbound: String? = null,
    val domain: List<String>? = null,
    @SerialName("domain_suffix") val domainSuffix: List<String>? = null,
    @SerialName("package_name") val packageName: List<String>? = null,
    @SerialName("rule_set") val ruleSet: List<String>? = null,
    @SerialName("udp_timeout") val udpTimeout: String? = null,
)

@Serializable
data class ExperimentalOptions(
    @SerialName("cache_file") val cacheFile: CacheFileOptions,
)

@Serializable
data class CacheFileOptions(
    val enabled: Boolean,
    val path: String,
)

@Serializable
data class HttpClient(
    val tag: String,
    val detour: String,
)
