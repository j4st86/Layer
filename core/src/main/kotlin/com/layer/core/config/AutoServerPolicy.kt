package com.layer.core.config

object AutoServerPolicy {
    val intervalMinutes: List<Int> = listOf(10, 15, 20, 25, 30, 60)
    const val defaultIntervalMinutes = 10
    const val probeAttempts = 3
    const val probeTimeoutMs = 2_500
    const val minServers = 2
    const val switchImprovementRatio = 0.20
    const val degradeRatio = 1.5
    const val failuresBeforeFullScan = 2
    const val cooldownMs = 120_000L
    const val networkChangeDebounceMs = 5_000L
    const val idleIntervalStretch = 3
    const val idleMinIntervalMinutes = 60

    fun canEnable(serverCount: Int): Boolean = serverCount >= minServers

    fun clampInterval(minutes: Int): Int =
        intervalMinutes.firstOrNull { it == minutes } ?: defaultIntervalMinutes

    fun monitorDelayMs(userMinutes: Int, interactive: Boolean): Long {
        val activeMs = clampInterval(userMinutes) * 60_000L
        if (interactive) return activeMs
        return maxOf(activeMs * idleIntervalStretch, idleMinIntervalMinutes * 60_000L)
    }

    fun shouldSkipPeriodicProbe(interactive: Boolean): Boolean = !interactive

    fun median(samples: List<Long>): Long? {
        if (samples.isEmpty()) return null
        val sorted = samples.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2
        }
    }

    fun shouldSwitch(currentMs: Long, candidateMs: Long): Boolean {
        if (candidateMs <= 0L || currentMs <= 0L) return false
        return candidateMs < currentMs * (1.0 - switchImprovementRatio)
    }

    fun isDegraded(currentMs: Long, lastGoodMs: Long): Boolean {
        if (lastGoodMs <= 0L) return false
        return currentMs > lastGoodMs * degradeRatio
    }
}
