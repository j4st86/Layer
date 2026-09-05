package com.layer.core.routing

import com.layer.core.i18n.copy
import java.net.IDN
import java.util.Locale

/**
 * Normalizes user-entered hostnames into a routing-safe domain suffix.
 *
 * Accepts values like `https://youtube.com/watch?v=1`, `*.example.com`, `EXAMPLE.COM`,
 * and Cyrillic IDN such as `сайт.ру` or `пример.рф` (stored as punycode for sing-box).
 */
object HostnameNormalizer {
    private val ipv4Regex = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
    private val hostnameRegex = Regex(
        """^(\*\.)?([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$""",
    )
    private val singleLabelRegex = Regex("""^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$""")

    data class Result(
        val domain: String,
        val error: String? = null,
    ) {
        val isValid: Boolean get() = error == null
    }

    fun normalize(raw: String): Result {
        var value = raw.trim()
        if (value.isEmpty()) {
            return Result("", copy("Enter a domain, for example google.com or сайт.ру", "Введите домен, например google.com или сайт.ру"))
        }

        value = value.replace("\\s+".toRegex(), "")
        value = value.lowercase(Locale.ROOT)
        value = value.removePrefix("http://").removePrefix("https://")
        value = value.substringBefore('/')
        value = value.substringBefore('?')
        value = value.substringBefore('#')
        value = value.substringBefore(':').trim('.')
        value = value.lowercase(Locale.ROOT)

        if (value.startsWith("*.")) {
            value = value.removePrefix("*.")
        }

        if (value.isEmpty()) {
            return Result("", copy("The domain is invalid", "Домен указан неверно"))
        }
        if (ipv4Regex.matches(value)) {
            return Result(value)
        }

        val ascii = toAscii(value) ?: return Result(value, copy("The domain is invalid", "Домен указан неверно"))
        if (ascii.contains('_') || ascii.startsWith("-") || ascii.endsWith("-")) {
            return Result(value, copy("The domain is invalid", "Домен указан неверно"))
        }
        if (!hostnameRegex.matches(ascii) && !singleLabelRegex.matches(ascii)) {
            return Result(value, copy("The domain is invalid", "Домен указан неверно"))
        }
        if (singleLabelRegex.matches(ascii) && !ascii.contains('.')) {
            return Result(value, copy("Add a zone, for example google.com or сайт.ру", "Добавьте зону, например google.com или сайт.ру"))
        }
        return Result(ascii)
    }

    /** Unicode form for UI. Routing still uses the ASCII/punycode from [normalize]. */
    fun display(domain: String): String {
        if (domain.isBlank()) return domain
        return try {
            IDN.toUnicode(domain, IDN.ALLOW_UNASSIGNED)
        } catch (_: IllegalArgumentException) {
            domain
        }
    }

    fun matches(ruleDomain: String, host: String): Boolean {
        val rule = normalize(ruleDomain)
        val target = normalize(host)
        if (!rule.isValid || !target.isValid) return false
        return target.domain == rule.domain || target.domain.endsWith("." + rule.domain)
    }

    private fun toAscii(value: String): String? {
        return try {
            IDN.toASCII(value, IDN.ALLOW_UNASSIGNED)
                .lowercase(Locale.ROOT)
                .takeIf { it.isNotBlank() }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
