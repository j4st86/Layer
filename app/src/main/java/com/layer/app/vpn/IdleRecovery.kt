package com.layer.app.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.layer.core.config.IdleRecoveryPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * SCREEN_ON / Doze / wifi↔cell recovery. Always Wake, never TUN reload:
 * see [IdleRecoveryPolicy].
 */
internal class IdleRecovery(
    private val context: Context,
    private val scope: CoroutineScope,
    private val isLive: () -> Boolean,
    private val wake: () -> Unit,
    private val onInteractive: () -> Unit,
    private val dbg: (String) -> Unit,
) {
    var startedElapsed = 0L
        private set
    private var lastIdleRecoverElapsed = 0L
    private var lastHandoffWakeElapsed = 0L
    private var lastScreenOffElapsed = 0L
    private var screenReceiver: BroadcastReceiver? = null
    private var idlePokeJob: Job? = null

    fun markStarted() {
        val now = SystemClock.elapsedRealtime()
        startedElapsed = now
        lastIdleRecoverElapsed = now
        lastScreenOffElapsed = 0L
    }

    fun clear() {
        startedElapsed = 0L
        lastIdleRecoverElapsed = 0L
        lastHandoffWakeElapsed = 0L
        lastScreenOffElapsed = 0L
    }

    fun recoverAfterIdle(reason: String, longIdleReload: Boolean = false) {
        if (!isLive()) {
            dbg(
                "[VPN] event=recover path=idle action=skip reason=$reason why=no-command-server",
            )
            return
        }
        val now = SystemClock.elapsedRealtime()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val hasNetwork = UnderlyingDns.pickUnderlyingNetwork(connectivity) != null
        val why = IdleRecoveryPolicy.skipReason(
            nowElapsed = now,
            startedElapsed = startedElapsed,
            lastRecoverElapsed = lastIdleRecoverElapsed,
            hasNetwork = hasNetwork,
        )
        if (why != null) {
            val remaining = IdleRecoveryPolicy.remainingDebounceMs(
                now,
                lastIdleRecoverElapsed,
                IdleRecoveryPolicy.debounceMs,
            )
            dbg(
                "[VPN] event=recover path=idle action=skip reason=$reason why=$why " +
                    "hasNetwork=$hasNetwork sinceLastMs=${IdleRecoveryPolicy.sinceLastLabel(now, lastIdleRecoverElapsed)} " +
                    "remainingMs=$remaining",
            )
            return
        }
        val sinceLast = IdleRecoveryPolicy.sinceLastLabel(now, lastIdleRecoverElapsed)
        lastIdleRecoverElapsed = now
        if (longIdleReload) {
            lastScreenOffElapsed = 0L
        }
        dbg(
            "[VPN] event=recover path=idle action=wake reason=$reason hasNetwork=$hasNetwork " +
                "sinceLastMs=$sinceLast",
        )
        wake()
    }

    fun recoverAfterHandoff(reason: String) {
        if (!isLive()) {
            dbg(
                "[VPN] event=recover path=handoff action=skip reason=$reason why=no-command-server",
            )
            return
        }
        val now = SystemClock.elapsedRealtime()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val hasNetwork = UnderlyingDns.pickUnderlyingNetwork(connectivity) != null
        val why = IdleRecoveryPolicy.handoffSkipReason(
            nowElapsed = now,
            startedElapsed = startedElapsed,
            lastHandoffWakeElapsed = lastHandoffWakeElapsed,
            hasNetwork = hasNetwork,
        )
        if (why != null) {
            val remaining = IdleRecoveryPolicy.remainingDebounceMs(
                now,
                lastHandoffWakeElapsed,
                IdleRecoveryPolicy.handoffDebounceMs,
            )
            dbg(
                "[VPN] event=recover path=handoff action=skip reason=$reason why=$why " +
                    "hasNetwork=$hasNetwork sinceLastMs=${IdleRecoveryPolicy.sinceLastLabel(now, lastHandoffWakeElapsed)} " +
                    "remainingMs=$remaining",
            )
            return
        }
        lastHandoffWakeElapsed = now
        dbg(
            "[VPN] event=recover path=handoff action=wake reason=$reason hasNetwork=$hasNetwork",
        )
        wake()
    }

    fun register() {
        if (screenReceiver != null) return
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        lastScreenOffElapsed = SystemClock.elapsedRealtime()
                        dbg("[VPN] event=screen action=off")
                        startIdlePoke()
                    }
                    Intent.ACTION_SCREEN_ON,
                    Intent.ACTION_USER_PRESENT,
                    -> {
                        dbg(
                            "[VPN] event=screen action=" +
                                if (intent.action == Intent.ACTION_USER_PRESENT) "user-present" else "on",
                        )
                        stopIdlePoke()
                        recoverAfterIdle(
                            if (intent.action == Intent.ACTION_USER_PRESENT) "user-present" else "screen-on",
                            longIdleReload = true,
                        )
                        onInteractive()
                    }
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                        val idle = this@IdleRecovery.context
                            .getSystemService(PowerManager::class.java).isDeviceIdleMode
                        dbg("[VPN] event=idle-mode on=$idle")
                        if (idle) {
                            if (lastScreenOffElapsed == 0L) {
                                lastScreenOffElapsed = SystemClock.elapsedRealtime()
                            }
                        } else {
                            recoverAfterIdle("idle-mode-off", longIdleReload = true)
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(
            context,
            screenReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val interactive = context.getSystemService(PowerManager::class.java)?.isInteractive != false
        if (!interactive) startIdlePoke()
    }

    fun unregister() {
        stopIdlePoke()
        val receiver = screenReceiver ?: return
        screenReceiver = null
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun startIdlePoke() {
        if (!isLive()) return
        if (idlePokeJob?.isActive == true) return
        dbg("[VPN] event=idle-poke action=start intervalMs=${IdleRecoveryPolicy.idlePokeMs}")
        idlePokeJob = scope.launch {
            while (isActive) {
                delay(IdleRecoveryPolicy.idlePokeMs)
                if (!isLive()) return@launch
                recoverAfterIdle("idle-poke")
            }
        }
    }

    private fun stopIdlePoke() {
        if (idlePokeJob != null) {
            dbg("[VPN] event=idle-poke action=stop")
        }
        idlePokeJob?.cancel()
        idlePokeJob = null
    }
}
