package com.layer.core.config

import com.layer.core.i18n.copy
import java.util.Base64

object SubscriptionParser {
    fun parse(body: String): Result<List<ParsedVlessLink>> {
        val text = decodeBody(body.trim().removePrefix("\uFEFF"))
        val parsed = text.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("vless://", ignoreCase = true) }
            .map { VlessLinkParser.parse(it) }
            .mapNotNull { it.getOrNull() }
            .toList()
        if (parsed.isEmpty()) {
            return Result.failure(IllegalArgumentException(copy("The subscription has no VLESS servers.", "В подписке нет VLESS-серверов.")))
        }
        return Result.success(parsed)
    }

    fun subscriptionName(url: String): String {
        val host = runCatching { java.net.URI(url.trim()).host }.getOrNull()
        return host?.ifBlank { null } ?: copy("Subscription", "Подписка")
    }

    private fun decodeBody(body: String): String {
        if (body.contains("vless://", ignoreCase = true)) return body
        val compact = body.replace("\\s".toRegex(), "")
        if (compact.isBlank()) return body
        decodeBase64(compact)?.let { decoded ->
            if (decoded.contains("vless://", ignoreCase = true)) return decoded
        }
        return body
    }

    private fun decodeBase64(value: String): String? {
        val padded = value + "=".repeat((4 - value.length % 4) % 4)
        val standard = runCatching {
            String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
        }.getOrNull()
        if (standard != null) return standard
        return runCatching {
            String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
        }.getOrNull()
    }
}
