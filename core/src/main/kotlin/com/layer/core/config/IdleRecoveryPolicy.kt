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
 * While the screen is off, Kotlin STAT still runs every 1–2 minutes but the Go
 * VLESS socket can sit half-dead until Doze's next maintenance window. A
 * periodic [Wake] (not reload, not resetNetwork) on that cadence keeps FCM and
 * Telegram from waiting for unlock.
 */
object IdleRecoveryPolicy {
    const val minUptimeMs = 4_000L
    const val debounceMs = 90_000L
    const val handoffDebounceMs = 15_000L
    const val idlePokeMs = 120_000L

    enum class Action { Skip, Wake }

    fun decide(
        nowElapsed: Long,
        startedElapsed: Long,
        lastRecoverElapsed: Long,
        screenOffElapsed: Long,
        longIdleReload: Boolean,
        hasNetwork: Boolean = true,
    ): Action {
        if (startedElapsed <= 0L) return Action.Skip
        if (nowElapsed - startedElapsed < minUptimeMs) return Action.Skip
        if (!hasNetwork) return Action.Skip
        if (lastRecoverElapsed > 0L && nowElapsed - lastRecoverElapsed < debounceMs) {
            return Action.Skip
        }
        return Action.Wake
    }

    fun decideHandoff(
        nowElapsed: Long,
        startedElapsed: Long,
        lastHandoffWakeElapsed: Long,
        hasNetwork: Boolean = true,
    ): Action {
        if (startedElapsed <= 0L) return Action.Skip
        if (nowElapsed - startedElapsed < minUptimeMs) return Action.Skip
        if (!hasNetwork) return Action.Skip
        if (lastHandoffWakeElapsed > 0L &&
            nowElapsed - lastHandoffWakeElapsed < handoffDebounceMs
        ) {
            return Action.Skip
        }
        return Action.Wake
    }
}
