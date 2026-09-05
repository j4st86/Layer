package com.layer.core.config

import com.layer.core.i18n.copy
import com.layer.core.model.DEFAULT_TLS_ALPN
import com.layer.core.model.DEFAULT_TLS_FINGERPRINT
import com.layer.core.model.DEFAULT_VLESS_FLOW
import com.layer.core.model.DEFAULT_VLESS_PORT
import com.layer.core.model.UUID_PLACEHOLDER
import com.layer.core.model.VlessServerConfig
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ParsedVlessLink(
    val uuid: String,
    val server: VlessServerConfig? = null,
)

object VlessLinkParser {
    val uuidRegex = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
    )

    fun parse(raw: String): Result<ParsedVlessLink> {
        val value = raw.trim().removePrefix("\uFEFF")
        if (value.isBlank() || value == UUID_PLACEHOLDER) {
            return Result.failure(IllegalArgumentException(copy("Paste a VLESS link or UUID", "Вставьте VLESS-ссылку или UUID")))
        }
        if (uuidRegex.matches(value)) {
            return Result.success(ParsedVlessLink(uuid = value.lowercase()))
        }
        if (value.startsWith("vless://", ignoreCase = true)) {
            return parseLink(value)
        }
        val embedded = uuidRegex.find(value)?.value
        if (embedded != null) {
            return Result.success(ParsedVlessLink(uuid = embedded.lowercase()))
        }
        return Result.failure(
            IllegalArgumentException(copy("Paste a full vless:// link or just the UUID.", "Вставьте vless:// ссылку целиком или только UUID.")),
        )
    }

    fun extractUuid(raw: String): String? = parse(raw).getOrNull()?.uuid

    private fun parseLink(raw: String): Result<ParsedVlessLink> {
        val withoutScheme = raw.substringAfter("://")
        val fragment = decodeFragment(raw)
        val withoutFragment = withoutScheme.substringBefore('#')
        val userAndHost = withoutFragment.substringBefore('?')
        val query = withoutFragment.substringAfter('?', missingDelimiterValue = "")
        if (!userAndHost.contains('@')) {
            return Result.failure(IllegalArgumentException(copy("No server found in the VLESS link.", "В VLESS-ссылке не найден сервер.")))
        }
        val uuid = userAndHost.substringBefore('@')
        if (!uuidRegex.matches(uuid)) {
            return Result.failure(IllegalArgumentException(copy("The UUID in the link looks invalid.", "UUID в ссылке выглядит некорректно.")))
        }
        val hostPort = userAndHost.substringAfter('@')
        val address = hostPort.substringBefore(':').trim()
        val port = hostPort.substringAfter(':', missingDelimiterValue = DEFAULT_VLESS_PORT.toString())
            .toIntOrNull() ?: DEFAULT_VLESS_PORT
        if (address.isBlank()) {
            return Result.failure(IllegalArgumentException(copy("The link has no server address.", "В ссылке нет адреса сервера.")))
        }
        val params = parseQuery(query)
        val security = params["security"].orEmpty().ifBlank { "tls" }
        val sni = params["sni"].orEmpty().ifBlank { address }
        val alpn = decode(params["alpn"]).ifBlank { DEFAULT_TLS_ALPN }
        val fingerprint = params["fp"].orEmpty().ifBlank {
            if (security.equals("reality", ignoreCase = true)) "chrome" else DEFAULT_TLS_FINGERPRINT
        }
        val network = VlessTransport.normalize(params["type"].orEmpty().ifBlank { VlessTransport.TCP })
        val extra = extraMap(params["extra"].orEmpty())
        val rawFlow = params["flow"].orEmpty()
        val flow = VlessTransport.effectiveFlow(
            network,
            if (rawFlow.isNotBlank()) rawFlow else if (VlessTransport.isTcp(network)) DEFAULT_VLESS_FLOW else "",
        )
        val path = params["path"].orEmpty().ifBlank { extra["path"].orEmpty() }
        val httpHost = params["host"].orEmpty().ifBlank { extra["host"].orEmpty() }
        val transportMode = params["mode"].orEmpty().ifBlank { extra["mode"].orEmpty() }
        val padding = params["x_padding_bytes"].orEmpty()
            .ifBlank { extra["xPaddingBytes"].orEmpty() }
            .ifBlank { extra["x_padding_bytes"].orEmpty() }
        val displayName = fragment.ifBlank { address }
        return Result.success(
            ParsedVlessLink(
                uuid = uuid.lowercase(),
                server = VlessServerConfig(
                    address = address,
                    port = port,
                    flow = flow,
                    network = network,
                    serverName = sni,
                    fingerprint = fingerprint,
                    alpn = alpn,
                    displayName = displayName,
                    security = security,
                    publicKey = RealityPublicKey.forSingBox(params["pbk"].orEmpty()),
                    shortId = params["sid"].orEmpty(),
                    spiderX = params["spx"].orEmpty(),
                    path = path,
                    httpHost = httpHost,
                    transportMode = transportMode,
                    xPaddingBytes = padding,
                ),
            ),
        )
    }

    private fun decodeFragment(raw: String): String {
        val hash = raw.indexOf('#')
        if (hash < 0 || hash == raw.lastIndex) return ""
        var decoded = decode(raw.substring(hash + 1).trim())
        if ('%' in decoded) decoded = decode(decoded)
        return decoded
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val key = part.substringBefore('=').lowercase()
            val value = decode(part.substringAfter('=', ""))
            key to value
        }.toMap()
    }

    private fun decode(value: String?): String {
        if (value.isNullOrBlank()) return ""
        // Query values use percent-encoding. URLDecoder also treats '+' as space,
        // which corrupts Reality public keys (standard Base64).
        return runCatching {
            URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)
        }.getOrDefault(value)
    }

    private fun extraMap(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val obj = runCatching { extraJson.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return emptyMap()
        return obj.mapNotNull { (key, value) ->
            val text = runCatching { value.jsonPrimitive.content }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            key to text
        }.toMap()
    }

    private val extraJson = Json { ignoreUnknownKeys = true }
}
