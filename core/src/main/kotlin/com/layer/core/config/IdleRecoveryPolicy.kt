package com.layer.core.config

/**
 * After Doze / a long lock, the VLESS TCP session is often dead while VpnService
 * still reports CONNECTED. [Wake] pokes libbox so the next dial recreates the
 * outbound without tearing TUN.
 *
 * A full reload closes the TUN fd (`closed previous`). Telegram's MTProto
 * sockets die with it, then the app retries into a half-ready stack. That is
 * worse than a stale outbound: reports show Telegram dying after a 3-minute
 * pocket lock, AOD/screen-timeout while chatting, and after airplane/Doze
 * with no network. Never reload from SCREEN_ON / idle-mode-off.
 *
 * With no underlying network, do nothing: a reload then bakes a dead outbound
 * into the new TUN, and Telegram cannot recover until the user toggles VPN.
 *
 * [resetNetwork] is intentionally not an action: it cancels every in-flight dial.
 * [Wake] during that retry storm does the same, so recoveries are debounced.
 *
 * wifi→cell is a different event from SCREEN_ON. The 90s idle debounce must not
 * swallow the handoff Wake: a screen-on 7s before Wi-Fi drop would otherwise
 * leave the VLESS outbound bound to a dead iface until the next idle recover.
 *
 * While the screen is off, a periodic [Wake] (not reload, not resetNetwork)
 * recovers a VLESS session that just stalled. FCM still arrives through
 * GMS DIRECT, so a quiet pocket must not poke. Live TUN bytes mean the
 * outbound is already working: poke only after recent traffic then silence.
 * The stall window is one poke period wide so a 120s ticker cannot skip it.
 *
 * Doze is the same: skip the poke, still Wake on SCREEN_ON / idle-mode-off.
 * libbox status polling is only for TUN-byte detection (auto-select quiet
 * window, idle-poke). It is not the home-screen ping. Keep it coarser than
 * 1 Hz, but finer than [AutoServerPolicy.trafficQuietMs] while interactive.
 */
object IdleRecoveryPolicy {
    const val minUptimeMs = 4_000L
    const val debounceMs = 90_000L
    const val handoffDebounceMs = 15_000L
    const val idlePokeMs = 120_000L
    const val idlePokeLiveMs = 120_000L
    const val idlePokeTrafficMs = 240_000L
    const val statusIntervalInteractiveNs = 10_000_000_000L
    const val statusIntervalIdleNs = 40_000_000_000L

    enum class Action { Skip, Wake }

    fun decide(
        nowElapsed: Long,
        startedElapsed: Long,
        lastRecoverElapsed: Long,
        screenOffElapsed: Long,
        longIdleReload: Boolean,
        hasNetwork: Boolean = true,
    ): Action = if (
        skipReason(
            nowElapsed = nowElapsed,
            startedElapsed = startedElapsed,
            lastRecoverElapsed = lastRecoverElapsed,
            hasNetwork = hasNetwork,
        ) == null
    ) {
        Action.Wake
    } else {
        Action.Skip
    }

    fun decideHandoff(
        nowElapsed: Long,
        startedElapsed: Long,
        lastHandoffWakeElapsed: Long,
        hasNetwork: Boolean = true,
    ): Action = if (
        handoffSkipReason(
            nowElapsed = nowElapsed,
            startedElapsed = startedElapsed,
            lastHandoffWakeElapsed = lastHandoffWakeElapsed,
            hasNetwork = hasNetwork,
        ) == null
    ) {
        Action.Wake
    } else {
        Action.Skip
    }

    /** Stable token for diagnostic logs. Null means Wake. */
    fun skipReason(
        nowElapsed: Long,
        startedElapsed: Long,
        lastRecoverElapsed: Long,
        hasNetwork: Boolean = true,
    ): String? {
        if (startedElapsed <= 0L) return "vpn-not-started"
        if (nowElapsed - startedElapsed < minUptimeMs) return "uptime"
        if (!hasNetwork) return "no-network"
        if (lastRecoverElapsed > 0L && nowElapsed - lastRecoverElapsed < debounceMs) {
            return "debounce"
        }
        return null
    }

    fun handoffSkipReason(
        nowElapsed: Long,
        startedElapsed: Long,
        lastHandoffWakeElapsed: Long,
        hasNetwork: Boolean = true,
    ): String? {
        if (startedElapsed <= 0L) return "vpn-not-started"
        if (nowElapsed - startedElapsed < minUptimeMs) return "uptime"
        if (!hasNetwork) return "no-network"
        if (lastHandoffWakeElapsed > 0L &&
            nowElapsed - lastHandoffWakeElapsed < handoffDebounceMs
        ) {
            return "debounce"
        }
        return null
    }

    /**
     * Periodic idle-poke only. SCREEN_ON / idle-mode-off still use [decide]
     * and must Wake even with no recent TUN bytes.
     *
     * [trafficAgeMs] is elapsed since the last STAT sample with bytes, or
     * [Long.MAX_VALUE] if there has never been TUN traffic.
     */
    fun idlePokeSkipReason(isDeviceIdle: Boolean, trafficAgeMs: Long): String? {
        if (isDeviceIdle) return "doze"
        if (trafficAgeMs > idlePokeTrafficMs) return "no-traffic"
        if (trafficAgeMs <= idlePokeLiveMs) return "live-traffic"
        return null
    }

    fun trafficAgeMs(nowElapsed: Long, lastTrafficElapsed: Long): Long {
        if (lastTrafficElapsed <= 0L) return Long.MAX_VALUE
        return (nowElapsed - lastTrafficElapsed).coerceAtLeast(0L)
    }

    fun remainingDebounceMs(nowElapsed: Long, lastElapsed: Long, windowMs: Long): Long {
        if (lastElapsed <= 0L) return 0L
        return (windowMs - (nowElapsed - lastElapsed)).coerceAtLeast(0L)
    }

    fun sinceLastLabel(nowElapsed: Long, lastElapsed: Long): String {
        if (lastElapsed <= 0L) return "never"
        return (nowElapsed - lastElapsed).toString()
    }
}
