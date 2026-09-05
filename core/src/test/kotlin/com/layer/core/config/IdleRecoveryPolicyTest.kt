package com.layer.core.config

import org.junit.Assert.assertEquals
import org.junit.Test

class IdleRecoveryPolicyTest {
    @Test
    fun skipsUntilVpnHasBeenUp() {
        assertEquals(
            IdleRecoveryPolicy.Action.Skip,
            IdleRecoveryPolicy.decide(
                nowElapsed = 3_000L,
                startedElapsed = 1_000L,
                lastRecoverElapsed = 0L,
                screenOffElapsed = 0L,
                longIdleReload = true,
            ),
        )
    }

    @Test
    fun debouncesRepeatedScreenOn() {
        assertEquals(
            IdleRecoveryPolicy.Action.Skip,
            IdleRecoveryPolicy.decide(
                nowElapsed = 40_000L,
                startedElapsed = 1_000L,
                lastRecoverElapsed = 25_000L,
                screenOffElapsed = 20_000L,
                longIdleReload = true,
            ),
        )
    }

    @Test
    fun wakesAfterShortLock() {
        assertEquals(
            IdleRecoveryPolicy.Action.Wake,
            IdleRecoveryPolicy.decide(
                nowElapsed = 70_000L,
                startedElapsed = 1_000L,
                lastRecoverElapsed = 0L,
                screenOffElapsed = 60_000L,
                longIdleReload = true,
            ),
        )
    }

    @Test
    fun reloadsAfterLongIdle() {
        assertEquals(
            IdleRecoveryPolicy.Action.Reload,
            IdleRecoveryPolicy.decide(
                nowElapsed = 4 * 60 * 60 * 1000L,
                startedElapsed = 1_000L,
                lastRecoverElapsed = 0L,
                screenOffElapsed = 30 * 60 * 1000L,
                longIdleReload = true,
            ),
        )
    }

    @Test
    fun longIdleReloadIgnoresRecentWakeDebounce() {
        assertEquals(
            IdleRecoveryPolicy.Action.Reload,
            IdleRecoveryPolicy.decide(
                nowElapsed = 4 * 60 * 60 * 1000L,
                startedElapsed = 1_000L,
                lastRecoverElapsed = 4 * 60 * 60 * 1000L - 2_000L,
                screenOffElapsed = 30 * 60 * 1000L,
                longIdleReload = true,
            ),
        )
    }

    @Test
    fun skipsWakeForNinetySecondsAfterReload() {
        assertEquals(
            IdleRecoveryPolicy.Action.Skip,
            IdleRecoveryPolicy.decide(
                nowElapsed = 80_000L,
                startedElapsed = 1_000L,
                lastRecoverElapsed = 10_000L,
                screenOffElapsed = 5_000L,
                longIdleReload = false,
            ),
        )
    }

    @Test
    fun wakesAfterDebounceWindow() {
        assertEquals(
            IdleRecoveryPolicy.Action.Wake,
            IdleRecoveryPolicy.decide(
                nowElapsed = 110_000L,
                startedElapsed = 1_000L,
                lastRecoverElapsed = 10_000L,
                screenOffElapsed = 5_000L,
                longIdleReload = false,
            ),
        )
    }
}
