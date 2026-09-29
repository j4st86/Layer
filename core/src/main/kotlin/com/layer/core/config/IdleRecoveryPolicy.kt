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
 * A partial wake lock held for the whole session keeps the phone out of
 * suspend. TCP keepalive inside sing-box is 3 minutes. Deep idle can still
 * freeze that timer, so one inexact idle alarm, 12 minutes apart, may send a
 * single wake. The lock around that ping is capped at 3 seconds. Recent
 * tunnel traffic skips the ping: media already keeps the session warm.
 */
object IdleRecoveryPolicy {
    const val minUptimeMs = 4_000L
    const val debounceMs = 90_000L
    const val handoffDebounceMs = 15_000L
    const val idleAlarmMs = 12 * 60 * 1000L
    const val idlePingLockMs = 3_000L
    const val idlePingSkipTrafficMs = 3 * 60 * 1000L
    const val alarmSlideMinMs = 60_000L

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

    fun remainingDebounceMs(nowElapsed: Long, lastElapsed: Long, windowMs: Long): Long {
        if (lastElapsed <= 0L) return 0L
        return (windowMs - (nowElapsed - lastElapsed)).coerceAtLeast(0L)
    }

    fun sinceLastLabel(nowElapsed: Long, lastElapsed: Long): String {
        if (lastElapsed <= 0L) return "never"
        return (nowElapsed - lastElapsed).toString()
    }

    /** True when the tunnel has been quiet long enough that NAT may have dropped. */
    fun shouldIdlePing(nowElapsed: Long, lastTrafficElapsed: Long): Boolean {
        if (lastTrafficElapsed <= 0L) return true
        return nowElapsed - lastTrafficElapsed > idlePingSkipTrafficMs
    }
}
