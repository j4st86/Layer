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
 */
object IdleRecoveryPolicy {
    const val minUptimeMs = 4_000L
    const val debounceMs = 90_000L

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
}
