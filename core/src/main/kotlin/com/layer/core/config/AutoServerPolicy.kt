package com.layer.core.config

import kotlin.math.roundToLong

/**
 * Auto-select probes TCP to `:443` only. That is cheap, but it is not tunnel
 * quality: a lucky 26 ms sample must not become the forever baseline, and a
 * working TUN must not be rebuilt because another origin is 20% faster.
 *
 * Soft switch requires 20% and [minSwitchDeltaMs], a confirm probe, cooldown,
 * and a quiet TUN. Failover needs two missed evaluates; a network change
 * settling window blocks force-switch so wifi↔cell timeouts do not tear TUN.
 */
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
    const val networkSettlingMs = 30_000L
    const val idleIntervalStretch = 3
    const val idleMinIntervalMinutes = 60
    const val goodEnoughMs = 80L
    const val minSwitchDeltaMs = 60L
    const val trafficQuietMs = 20_000L
    const val ewmaAlpha = 0.3

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

    fun ewma(previous: Long?, sample: Long): Long {
        if (previous == null || previous <= 0L) return sample
        return (ewmaAlpha * sample + (1.0 - ewmaAlpha) * previous).roundToLong()
    }

    fun isGoodEnough(currentMs: Long): Boolean = currentMs in 1..goodEnoughMs

    fun shouldSwitch(currentMs: Long, candidateMs: Long): Boolean {
        if (candidateMs <= 0L || currentMs <= 0L) return false
        if (currentMs - candidateMs < minSwitchDeltaMs) return false
        return candidateMs < currentMs * (1.0 - switchImprovementRatio)
    }

    fun isDegraded(currentMs: Long, baselineMs: Long): Boolean {
        if (currentMs <= 0L || baselineMs <= 0L) return false
        if (isGoodEnough(currentMs)) return false
        return currentMs > baselineMs * degradeRatio
    }

    fun shouldFailover(consecutiveFailures: Int): Boolean =
        consecutiveFailures >= failuresBeforeFullScan

    fun isNetworkSettling(nowElapsed: Long, lastChangeElapsed: Long): Boolean {
        if (lastChangeElapsed <= 0L) return false
        return nowElapsed - lastChangeElapsed < networkSettlingMs
    }
}
