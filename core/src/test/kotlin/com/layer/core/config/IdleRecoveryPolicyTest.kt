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
    fun skipsWhenThereIsNoNetwork() {
        assertEquals(
            IdleRecoveryPolicy.Action.Skip,
            IdleRecoveryPolicy.decide(
                nowElapsed = 4 * 60 * 60 * 1000L,
                startedElapsed = 1_000L,
                lastRecoverElapsed = 0L,
                screenOffElapsed = 30 * 60 * 1000L,
                longIdleReload = true,
                hasNetwork = false,
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
    fun wakesAfterAFewMinutesInAPocket() {
        assertEquals(
            IdleRecoveryPolicy.Action.Wake,
            IdleRecoveryPolicy.decide(
                nowElapsed = 5 * 60_000L,
                startedElapsed = 1_000L,
                lastRecoverElapsed = 0L,
                screenOffElapsed = 60_000L,
                longIdleReload = true,
            ),
        )
    }

    @Test
    fun wakesAfterLongIdleInsteadOfReloadingTun() {
        assertEquals(
            IdleRecoveryPolicy.Action.Wake,
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
    fun longIdleStillRespectsDebounce() {
        assertEquals(
            IdleRecoveryPolicy.Action.Skip,
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

    @Test
    fun handoffWakeIgnoresIdleDebounce() {
        assertEquals(
            IdleRecoveryPolicy.Action.Wake,
            IdleRecoveryPolicy.decideHandoff(
                nowElapsed = 40_000L,
                startedElapsed = 1_000L,
                lastHandoffWakeElapsed = 0L,
            ),
        )
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
    fun handoffWakeHasItsOwnDebounce() {
        assertEquals(
            IdleRecoveryPolicy.Action.Skip,
            IdleRecoveryPolicy.decideHandoff(
                nowElapsed = 40_000L,
                startedElapsed = 1_000L,
                lastHandoffWakeElapsed = 30_000L,
            ),
        )
        assertEquals(
            IdleRecoveryPolicy.Action.Wake,
            IdleRecoveryPolicy.decideHandoff(
                nowElapsed = 50_000L,
                startedElapsed = 1_000L,
                lastHandoffWakeElapsed = 30_000L,
            ),
        )
    }

    @Test
    fun handoffWakeSkipsWhenThereIsNoNetwork() {
        assertEquals(
            IdleRecoveryPolicy.Action.Skip,
            IdleRecoveryPolicy.decideHandoff(
                nowElapsed = 40_000L,
                startedElapsed = 1_000L,
                lastHandoffWakeElapsed = 0L,
                hasNetwork = false,
            ),
        )
    }
}
