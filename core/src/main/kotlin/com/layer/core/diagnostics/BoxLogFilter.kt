package com.layer.core.diagnostics

/**
 * sing-box info logs every outbound during music. That wipes the diagnostic
 * buffer, so overnight [VPN]/[AUTO] history is gone. Keep warn+error only.
 */
object BoxLogFilter {
    fun keep(level: String, @Suppress("UNUSED_PARAMETER") text: String): Boolean {
        return when (level.lowercase()) {
            "panic", "fatal", "error", "warn", "warning" -> true
            else -> false
        }
    }

    fun fingerprint(level: String, text: String): String {
        val stripped = text
            .replace(Regex("""(?i)(?:INFO|ERROR|DEBUG|TRACE|WARN|WARNING|FATAL|PANIC)\[\d+]"""), "")
            .replace(Regex("""\[\d+ \d+(?:\.\d+)?(?:ms|s)\]"""), "[]")
            .replace(Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b"""), "ip")
        return "$level $stripped".trim()
    }
}

class BoxLogRateLimiter(
    private val windowMs: Long = 8_000L,
    private val burst: Int = 3,
    private val maxKeys: Int = 48,
) {
    private data class Bucket(
        var windowStart: Long,
        var count: Int,
        var hidden: Int,
    )

    private val buckets = LinkedHashMap<String, Bucket>(maxKeys, 0.75f, true)

    @Synchronized
    fun allow(fingerprint: String, nowMs: Long): Decision {
        val existing = buckets[fingerprint]
        if (existing == null) {
            prune(nowMs)
            buckets[fingerprint] = Bucket(nowMs, 1, 0)
            return Decision.Emit
        }
        if (nowMs - existing.windowStart >= windowMs) {
            val hidden = existing.hidden
            existing.windowStart = nowMs
            existing.count = 1
            existing.hidden = 0
            return if (hidden > 0) Decision.EmitHidden(hidden) else Decision.Emit
        }
        existing.count += 1
        if (existing.count <= burst) return Decision.Emit
        existing.hidden += 1
        return Decision.Drop
    }

    private fun prune(nowMs: Long) {
        if (buckets.size < maxKeys) return
        val stale = buckets.entries
            .filter { nowMs - it.value.windowStart >= windowMs }
            .map { it.key }
        stale.forEach { buckets.remove(it) }
        while (buckets.size >= maxKeys) {
            val oldest = buckets.keys.firstOrNull() ?: break
            buckets.remove(oldest)
        }
    }

    sealed class Decision {
        data object Emit : Decision()
        data object Drop : Decision()
        data class EmitHidden(val count: Int) : Decision()
    }
}
