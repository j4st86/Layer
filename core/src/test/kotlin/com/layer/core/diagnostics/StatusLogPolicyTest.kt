package com.layer.core.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusLogPolicyTest {
    @Test
    fun skipsWhileBytesAreMoving() {
        assertFalse(StatusLogPolicy.shouldLog(1_000L, 0L, uplink = 12_000L, downlink = 0L))
        assertFalse(StatusLogPolicy.shouldLog(1_000L, 0L, uplink = 0L, downlink = 80_000L))
    }

    @Test
    fun heartbeatsWhenQuiet() {
        assertTrue(StatusLogPolicy.shouldLog(1_000L, lastLogMs = 0L, uplink = 0L, downlink = 0L))
        assertFalse(StatusLogPolicy.shouldLog(60_000L, lastLogMs = 1_000L, uplink = 0L, downlink = 0L))
        assertTrue(
            StatusLogPolicy.shouldLog(
                1_000L + StatusLogPolicy.heartbeatMs,
                lastLogMs = 1_000L,
                uplink = 0L,
                downlink = 0L,
            ),
        )
    }
}
