package com.layer.core.config

object QrPayload {
    fun extract(raw: String): String {
        val text = raw.trim().removePrefix("\uFEFF")
        if (text.isBlank()) return text
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        lines.firstOrNull { it.startsWith("vless://", ignoreCase = true) }?.let { return it }
        lines.firstOrNull {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }?.let { return it }
        return text
    }
}
