package com.layer.core.config

import com.layer.core.i18n.copy
import java.net.URI

sealed class VpnConnectionInput {
    abstract val raw: String

    data class VlessLink(override val raw: String) : VpnConnectionInput()
    data class Subscription(override val raw: String) : VpnConnectionInput()
}

object VpnConnectionParser {
    val REJECT_MESSAGE: String
        get() = copy(
            "This is not a VPN link. Use VLESS (vless://) or an HTTPS subscription.",
            "Это не ссылка VPN. Нужен VLESS (vless://) или HTTPS-подписка.",
        )
    val OTHER_PROTOCOL_MESSAGE: String
        get() = copy(
            "Only VLESS and subscription links are supported.",
            "Поддерживаются только VLESS и ссылка подписки.",
        )

    fun parse(raw: String): Result<VpnConnectionInput> {
        val value = QrPayload.extract(raw)
        if (value.isBlank()) {
            return Result.failure(
                IllegalArgumentException(
                    copy("Paste a VLESS link or a subscription.", "Вставьте VLESS-ссылку или подписку."),
                ),
            )
        }
        if (isUnsupportedProxyScheme(value)) {
            return Result.failure(IllegalArgumentException(OTHER_PROTOCOL_MESSAGE))
        }
        if (value.startsWith("vless://", ignoreCase = true)) {
            val parsed = VlessLinkParser.parse(value).getOrElse { return Result.failure(it) }
            if (parsed.server == null) {
                return Result.failure(
                    IllegalArgumentException(
                        copy("No server found in the VLESS link.", "В VLESS-ссылке не найден сервер."),
                    ),
                )
            }
            return Result.success(VpnConnectionInput.VlessLink(value))
        }
        if (value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
        ) {
            return parseSubscriptionUrl(value)
        }
        return Result.failure(IllegalArgumentException(REJECT_MESSAGE))
    }

    fun parseQr(raw: String): Result<VpnConnectionInput> = parse(raw)

    private fun parseSubscriptionUrl(value: String): Result<VpnConnectionInput> {
        val uri = runCatching { URI(value) }.getOrElse {
            return Result.failure(IllegalArgumentException(REJECT_MESSAGE))
        }
        val host = uri.host?.lowercase()?.removePrefix("www.")
        if (host.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException(REJECT_MESSAGE))
        }
        if (isRejectedHost(host)) {
            return Result.failure(IllegalArgumentException(REJECT_MESSAGE))
        }
        val path = uri.path.orEmpty()
        val query = uri.query.orEmpty()
        if (path.length <= 1 && query.isBlank()) {
            return Result.failure(IllegalArgumentException(REJECT_MESSAGE))
        }
        return Result.success(VpnConnectionInput.Subscription(value))
    }

    private fun isUnsupportedProxyScheme(value: String): Boolean {
        val scheme = value.substringBefore("://", missingDelimiterValue = "").lowercase()
        return scheme in unsupportedSchemes
    }

    private fun isRejectedHost(host: String): Boolean =
        rejectedHosts.any { host == it || host.endsWith(".$it") }

    private val unsupportedSchemes = setOf(
        "vmess", "trojan", "ss", "ssr", "hysteria", "hysteria2", "hy2",
        "tuic", "wireguard", "wg", "socks", "socks5",
        "wifi", "mailto", "tel", "geo", "smsto",
    )

    private val rejectedHosts = setOf(
        "google.com", "google.ru", "youtube.com", "youtu.be",
        "instagram.com", "facebook.com", "fb.com", "twitter.com", "x.com",
        "tiktok.com", "wikipedia.org", "amazon.com", "reddit.com",
        "vk.com", "yandex.ru", "yandex.com", "mail.ru", "ok.ru",
        "whatsapp.com", "netflix.com", "twitch.tv", "linkedin.com",
        "apple.com", "microsoft.com", "t.me", "telegram.org",
    )
}
