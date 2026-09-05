package com.layer.core.diagnostics

object Branding {
    fun libboxVersion(raw: String?): String {
        val value = raw?.trim().orEmpty().ifBlank { "unknown" }
        return value.replace("pixelnet", "layer", ignoreCase = true)
    }
}
