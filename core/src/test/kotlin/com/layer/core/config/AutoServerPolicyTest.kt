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
    fun switchesOnlyWhenTwentyPercentAndSixtyMsBetter() {
        assertFalse(AutoServerPolicy.shouldSwitch(50, 46))
        assertFalse(AutoServerPolicy.shouldSwitch(100, 70))
        assertFalse(AutoServerPolicy.shouldSwitch(100, 85))
        assertFalse(AutoServerPolicy.shouldSwitch(120, 70))
        assertFalse(AutoServerPolicy.shouldSwitch(101, 53))
        assertTrue(AutoServerPolicy.shouldSwitch(150, 70))
        assertTrue(AutoServerPolicy.shouldSwitch(101, 40))
    }

    @Test
    fun ignoresDegradeWhenStillGoodEnough() {
        assertFalse(AutoServerPolicy.isDegraded(60, 50))
        assertFalse(AutoServerPolicy.isDegraded(80, 50))
        assertTrue(AutoServerPolicy.isDegraded(130, 50))
        assertTrue(AutoServerPolicy.isGoodEnough(80))
        assertFalse(AutoServerPolicy.isGoodEnough(81))
    }

    @Test
    fun ewmaMovesTowardSampleWithoutStickingToTheMinimum() {
        assertEquals(26L, AutoServerPolicy.ewma(null, 26))
        assertEquals(49L, AutoServerPolicy.ewma(26, 101))
        assertEquals(52L, AutoServerPolicy.ewma(50, 55))
    }

    @Test
    fun failoversAfterTwoMisses() {
        assertFalse(AutoServerPolicy.shouldFailover(0))
        assertFalse(AutoServerPolicy.shouldFailover(1))
        assertTrue(AutoServerPolicy.shouldFailover(2))
    }

    @Test
    fun settlingWindowBlocksForceSwitchAfterNetworkChange() {
        assertFalse(AutoServerPolicy.isNetworkSettling(10_000L, 0L))
        assertTrue(AutoServerPolicy.isNetworkSettling(20_000L, 10_000L))
        assertFalse(AutoServerPolicy.isNetworkSettling(50_000L, 10_000L))
        assertEquals(20_000L, AutoServerPolicy.remainingSettlingMs(20_000L, 10_000L))
        assertEquals(0L, AutoServerPolicy.remainingSettlingMs(50_000L, 10_000L))
        assertFalse(AutoServerPolicy.countMissTowardFailover(settling = true))
        assertTrue(AutoServerPolicy.countMissTowardFailover(settling = false))
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
