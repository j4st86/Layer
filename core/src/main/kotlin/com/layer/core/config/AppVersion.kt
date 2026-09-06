package com.layer.core.config

object AppVersion {
    fun normalize(raw: String): String {
        return raw.trim().removePrefix("v").removePrefix("V").substringBefore('+')
    }

    /** Negative if [installed] is older than [remote]. */
    fun compare(installed: String, remote: String): Int {
        val left = parts(installed)
        val right = parts(remote)
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    fun isNewer(installed: String, remote: String): Boolean = compare(installed, remote) < 0

    private fun parts(raw: String): List<Int> {
        val core = normalize(raw).substringBefore('-')
        if (core.isBlank()) return listOf(0)
        return core.split('.').map { it.toIntOrNull() ?: 0 }
    }
}
