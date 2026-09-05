package com.layer.core.config

/**
 * After Doze / a long lock, the VLESS TCP session is often dead while VpnService
 * still reports CONNECTED. A short [Wake] is enough when the screen was off briefly.
 * After a long idle, only a full reload recreates the outbound — the same thing
 * the in-app disconnect/connect toggle does.
 *
 * [resetNetwork] is intentionally not an action: it cancels every in-flight dial
 * and Telegram then retries into a half-torn stack. [Wake] during that retry
 * storm does the same, so recoveries are debounced well past a typical reconnect.
 */
object IdleRecoveryPolicy {
    const val minUptimeMs = 4_000L
    const val debounceMs = 90_000L
    const val reloadAfterIdleMs = 180_000L

    enum class Action { Skip, Wake, Reload }

    fun decide(
        nowElapsed: Long,
        startedElapsed: Long,
        lastRecoverElapsed: Long,
        screenOffElapsed: Long,
        longIdleReload: Boolean,
    ): Action {
        if (startedElapsed <= 0L) return Action.Skip
        if (nowElapsed - startedElapsed < minUptimeMs) return Action.Skip
        val idleMs = if (screenOffElapsed > 0L) nowElapsed - screenOffElapsed else 0L
        if (longIdleReload && idleMs >= reloadAfterIdleMs) return Action.Reload
        if (lastRecoverElapsed > 0L && nowElapsed - lastRecoverElapsed < debounceMs) {
            return Action.Skip
        }
        return Action.Wake
    }
}
