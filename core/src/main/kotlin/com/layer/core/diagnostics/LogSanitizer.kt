package com.layer.core.diagnostics

object LogSanitizer {
    private val uuidRegex = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
    )
    private val secretJsonRegex = Regex(
        "\"(uuid|public_key|short_id|pbk|sid|spider_x)\"\\s*:\\s*\"[^\"]+\"",
    )
    private val ansiRegex = Regex("\\u001B\\[[0-9;]*[A-Za-z]")

    fun sanitize(message: String?): String {
        if (message.isNullOrBlank()) return ""
        val withoutAnsi = ansiRegex.replace(message, "")
        val withoutSecrets = secretJsonRegex.replace(withoutAnsi) { match ->
            val key = match.groupValues[1]
            "\"$key\":\"[redacted]\""
        }
        return uuidRegex.replace(withoutSecrets, "[redacted]")
            .replace("pixelnet", "layer", ignoreCase = true)
    }
}
