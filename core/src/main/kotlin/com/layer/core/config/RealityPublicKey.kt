package com.layer.core.config

/**
 * sing-box decodes Reality `public_key` with Go `base64.RawURLEncoding`
 * (URL-safe alphabet, no padding). x-ui / v2rayN links may use standard
 * Base64 (`+/`, `=`) or URL-safe (`-_`). Normalize before emitting JSON.
 */
object RealityPublicKey {
    fun forSingBox(raw: String): String {
        if (raw.isBlank()) return ""
        return raw.replace(" ", "+")
            .trim()
            .trimEnd('=')
            .replace('+', '-')
            .replace('/', '_')
    }
}
