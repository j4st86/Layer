package com.layer.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoServerPolicyTest {
    @Test
    fun requiresAtLeastTwoServers() {
        assertFalse(AutoServerPolicy.canEnable(0))
        assertFalse(AutoServerPolicy.canEnable(1))
        assertTrue(AutoServerPolicy.canEnable(2))
        assertTrue(AutoServerPolicy.canEnable(3))
    }

    @Test
    fun medianUsesMiddleValue() {
        assertEquals(50L, AutoServerPolicy.median(listOf(50, 40, 90)))
        assertEquals(45L, AutoServerPolicy.median(listOf(40, 50)))
        assertEquals(null, AutoServerPolicy.median(emptyList()))
    }

    @Test
    fun switchesOnlyWhenTwentyPercentBetter() {
        assertFalse(AutoServerPolicy.shouldSwitch(50, 46))
        assertTrue(AutoServerPolicy.shouldSwitch(100, 70))
        assertFalse(AutoServerPolicy.shouldSwitch(100, 85))
    }

    @Test
    fun detectsDegradedLatency() {
        assertFalse(AutoServerPolicy.isDegraded(60, 50))
        assertTrue(AutoServerPolicy.isDegraded(80, 50))
    }

    @Test
    fun clampsUnknownIntervalToDefault() {
        assertEquals(10, AutoServerPolicy.clampInterval(7))
        assertEquals(10, AutoServerPolicy.clampInterval(5))
        assertEquals(15, AutoServerPolicy.clampInterval(15))
        assertEquals(20, AutoServerPolicy.clampInterval(20))
        assertEquals(25, AutoServerPolicy.clampInterval(25))
        assertEquals(10, AutoServerPolicy.defaultIntervalMinutes)
    }

    @Test
    fun stretchesIntervalWhenDeviceIsIdle() {
        assertEquals(10 * 60_000L, AutoServerPolicy.monitorDelayMs(10, interactive = true))
        assertEquals(60 * 60_000L, AutoServerPolicy.monitorDelayMs(10, interactive = false))
        assertEquals(180 * 60_000L, AutoServerPolicy.monitorDelayMs(60, interactive = false))
        assertTrue(AutoServerPolicy.shouldSkipPeriodicProbe(interactive = false))
        assertFalse(AutoServerPolicy.shouldSkipPeriodicProbe(interactive = true))
    }
}
