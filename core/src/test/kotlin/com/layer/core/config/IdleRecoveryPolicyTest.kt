package com.layer.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun idlePokeIsLongerThanDebounceAndShorterThanDozeWindow() {
        assertTrue(IdleRecoveryPolicy.idlePokeMs > IdleRecoveryPolicy.debounceMs)
        assertTrue(IdleRecoveryPolicy.idlePokeMs < 15 * 60 * 1000L)
        assertTrue(
            IdleRecoveryPolicy.idlePokeTrafficMs >
                IdleRecoveryPolicy.statusIntervalIdleNs / 1_000_000L,
        )
        assertTrue(
            IdleRecoveryPolicy.idlePokeLiveMs >
                2 * IdleRecoveryPolicy.statusIntervalIdleNs / 1_000_000L,
        )
        assertTrue(IdleRecoveryPolicy.idlePokeLiveMs < IdleRecoveryPolicy.idlePokeTrafficMs)
        assertTrue(
            IdleRecoveryPolicy.idlePokeTrafficMs - IdleRecoveryPolicy.idlePokeLiveMs >=
                IdleRecoveryPolicy.idlePokeMs,
        )
        assertTrue(
            IdleRecoveryPolicy.statusIntervalInteractiveNs / 1_000_000L <
                AutoServerPolicy.trafficQuietMs,
        )
    }

    @Test
    fun idlePokeSkipsDozeLiveStreamAndQuietTun() {
        assertEquals(
            "doze",
            IdleRecoveryPolicy.idlePokeSkipReason(true, IdleRecoveryPolicy.idlePokeLiveMs + 1L),
        )
        assertEquals(
            "no-traffic",
            IdleRecoveryPolicy.idlePokeSkipReason(false, Long.MAX_VALUE),
        )
        assertEquals(
            "no-traffic",
            IdleRecoveryPolicy.idlePokeSkipReason(false, IdleRecoveryPolicy.idlePokeTrafficMs + 1L),
        )
        assertEquals(
            "live-traffic",
            IdleRecoveryPolicy.idlePokeSkipReason(false, 20_000L),
        )
        assertEquals(
            "live-traffic",
            IdleRecoveryPolicy.idlePokeSkipReason(false, IdleRecoveryPolicy.idlePokeLiveMs),
        )
        assertEquals(
            null,
            IdleRecoveryPolicy.idlePokeSkipReason(false, IdleRecoveryPolicy.idlePokeLiveMs + 1L),
        )
    }

    @Test
    fun idlePokeTickerCannotSkipTheStallWindow() {
        val liveAge = IdleRecoveryPolicy.idlePokeLiveMs
        assertEquals(
            "live-traffic",
            IdleRecoveryPolicy.idlePokeSkipReason(false, liveAge),
        )
        val nextTickAge = liveAge + IdleRecoveryPolicy.idlePokeMs
        assertEquals(
            null,
            IdleRecoveryPolicy.idlePokeSkipReason(false, nextTickAge),
        )
        assertTrue(nextTickAge <= IdleRecoveryPolicy.idlePokeTrafficMs)
        val midPhaseAge = 80_000L
        assertEquals(
            "live-traffic",
            IdleRecoveryPolicy.idlePokeSkipReason(false, midPhaseAge),
        )
        assertEquals(
            null,
            IdleRecoveryPolicy.idlePokeSkipReason(
                false,
                midPhaseAge + IdleRecoveryPolicy.idlePokeMs,
            ),
        )
    }

    @Test
    fun trafficAgeTreatsMissingSamplesAsQuiet() {
        assertEquals(Long.MAX_VALUE, IdleRecoveryPolicy.trafficAgeMs(90_000L, 0L))
        assertEquals(40_000L, IdleRecoveryPolicy.trafficAgeMs(90_000L, 50_000L))
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

    @Test
    fun skipReasonMatchesDecide() {
        assertEquals(
            "vpn-not-started",
            IdleRecoveryPolicy.skipReason(
                nowElapsed = 3_000L,
                startedElapsed = 0L,
                lastRecoverElapsed = 0L,
            ),
        )
        assertEquals(
            "no-network",
            IdleRecoveryPolicy.skipReason(
                nowElapsed = 60_000L,
                startedElapsed = 1_000L,
                lastRecoverElapsed = 0L,
                hasNetwork = false,
            ),
        )
        assertEquals(
            "debounce",
            IdleRecoveryPolicy.skipReason(
                nowElapsed = 40_000L,
                startedElapsed = 1_000L,
                lastRecoverElapsed = 25_000L,
            ),
        )
        assertEquals(
            null,
            IdleRecoveryPolicy.skipReason(
                nowElapsed = 110_000L,
                startedElapsed = 1_000L,
                lastRecoverElapsed = 10_000L,
            ),
        )
        assertEquals(
            "debounce",
            IdleRecoveryPolicy.handoffSkipReason(
                nowElapsed = 40_000L,
                startedElapsed = 1_000L,
                lastHandoffWakeElapsed = 30_000L,
            ),
        )
    }
}
