package com.layer.core.config

/**
 * Ad-block lists are refreshed on a long interval. The toggle and period
 * are stored now; download and routing land in follow-up work.
 */
object AdBlockPolicy {
    val intervalDays: List<Int> = listOf(1, 2, 3, 7)
    const val defaultIntervalDays = 7

    fun clampIntervalDays(days: Int): Int =
        intervalDays.firstOrNull { it == days } ?: defaultIntervalDays
}
