package com.layer.core.config

import org.junit.Assert.assertEquals
import org.junit.Test

class AdBlockPolicyTest {
    @Test
    fun keepsKnownIntervals() {
        assertEquals(1, AdBlockPolicy.clampIntervalDays(1))
        assertEquals(2, AdBlockPolicy.clampIntervalDays(2))
        assertEquals(3, AdBlockPolicy.clampIntervalDays(3))
        assertEquals(7, AdBlockPolicy.clampIntervalDays(7))
    }

    @Test
    fun unknownIntervalFallsBackToSevenDays() {
        assertEquals(7, AdBlockPolicy.clampIntervalDays(0))
        assertEquals(7, AdBlockPolicy.clampIntervalDays(14))
        assertEquals(7, AdBlockPolicy.clampIntervalDays(-1))
    }
}
