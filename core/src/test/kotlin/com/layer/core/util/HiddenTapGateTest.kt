package com.layer.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenTapGateTest {
    @Test
    fun unlocksOnSevenConsecutiveTaps() {
        val gate = HiddenTapGate()
        repeat(6) { index ->
            val result = gate.tap(index * 200L)
            assertTrue(result is HiddenTapResult.Progress)
            assertEquals(6 - index, (result as HiddenTapResult.Progress).remaining)
        }
        assertEquals(HiddenTapResult.Unlocked, gate.tap(1_200L))
        assertEquals(0, gate.progress)
    }

    @Test
    fun resetsWhenTapsAreTooFarApart() {
        val gate = HiddenTapGate()
        repeat(4) { gate.tap(it * 200L) }
        val afterGap = gate.tap(10_000L)
        assertEquals(HiddenTapResult.Progress(1, 6), afterGap)
    }

    @Test
    fun resetClearsProgress() {
        val gate = HiddenTapGate()
        repeat(5) { gate.tap(it * 100L) }
        gate.reset()
        assertEquals(0, gate.progress)
        assertEquals(HiddenTapResult.Progress(1, 6), gate.tap(1_000L))
    }
}
