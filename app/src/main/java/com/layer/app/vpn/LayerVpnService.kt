package com.layer.app.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.layer.app.LayerApp
import com.layer.app.R
import com.layer.app.data.AdBlockDownloader
import com.layer.app.data.RuleSetDownloader
import com.layer.app.box.BoxCommandCallbacks
import com.layer.app.box.BoxHost
import com.layer.app.box.BoxRuntime
import com.layer.app.box.libbox.LibboxSession
import com.layer.core.config.RuleSetCatalog
import com.layer.core.config.AutoServerPolicy
import com.layer.core.config.IdleRecoveryPolicy
import com.layer.core.diagnostics.ErrorMapper
import com.layer.core.diagnostics.LogSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LayerVpnService : VpnService(), BoxHost, BoxCommandCallbacks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val notification by lazy { VpnNotification(this) }
    private var box: LibboxSession? = null

    private val container get() = (application as LayerApp).container
    private var stopping = false
    private var serverName: String = ""
    private var screenReceiver: BroadcastReceiver? = null
    private var startedElapsed = 0L
    private var lastIdleRecoverElapsed = 0L
    private var lastHandoffWakeElapsed = 0L
    private var lastScreenOffElapsed = 0L
    private var idlePokeJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_STOP) {
            intent?.getStringExtra(EXTRA_SERVER_NAME)?.takeIf { it.isNotBlank() }?.let {
                serverName = it
            }
            notification.startForeground(VpnStatusStore.status.value.state, serverName.ifBlank { null })
        }
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                scope.launch { reloadInternal() }
                return START_STICKY
            }
            ACTION_REWIRE -> {
                dbg("[VPN] event=rewire source=notification")
                scope.launch { reloadInternal() }
                return START_STICKY
            }
            else -> {
                // START, Always-on VPN, or reboot: system may pass a null action.
                stopping = false
                val state = VpnStatusStore.status.value.state
                if (box != null &&
                    (state == VpnConnectionState.CONNECTED ||
                        state == VpnConnectionState.CONNECTING ||
                        state == VpnConnectionState.RECONNECTING)
                ) {
                    dbg("[VPN] event=start action=skip reason=already-running state=$state")
                    return START_STICKY
                }
                scope.launch { startVpn() }
                return START_STICKY
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val state = VpnStatusStore.status.value.state
        if (!stopping &&
            (state == VpnConnectionState.CONNECTED ||
                state == VpnConnectionState.CONNECTING ||
                state == VpnConnectionState.RECONNECTING)
        ) {
            notification.startForeground(state, serverName.ifBlank { null })
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = this
    }

    override fun onDestroy() {
        if (running === this) running = null
        stopVpn()
        scope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    private suspend fun startVpn() = lifecycleMutex.withLock { startVpnLocked() }

    private suspend fun startVpnLocked() {
        if (stopping) return
        val already = VpnStatusStore.status.value.state
        if (box != null &&
            (already == VpnConnectionState.CONNECTED ||
                already == VpnConnectionState.CONNECTING ||
                already == VpnConnectionState.RECONNECTING)
        ) {
            dbg("[VPN] event=start action=skip reason=already-running state=$already")
            return
        }
        var settings = container.repository.currentSnapshot().settings
        if (stopping) return
        val autoPick = settings.autoSelectServerEnabled &&
            AutoServerPolicy.canEnable(settings.servers.size)
        dbg(
            "[AUTO] event=startVpn auto=${settings.autoSelectServerEnabled} " +
                "servers=${settings.servers.size} pick=$autoPick",
        )
        updateStatus(
            VpnConnectionState.CONNECTING,
            if (autoPick) getString(R.string.status_checking_servers) else getString(R.string.status_connecting_ellipsis),
        )
        if (autoPick) {
            container.autoServerSelector.prepareBestServer()
            settings = container.repository.currentSnapshot().settings
            dbg("[AUTO] event=startVpn action=after-pick server=${settings.activeConnectionName()}")
        }
        serverName = settings.activeConnectionName()
        val host = settings.server.address
        dbg(
            "[VPN] event=start server=${settings.activeConnectionName()} " +
                "host=$host:${settings.server.port} sni=${settings.server.serverName} " +
                "security=${settings.server.security} fp=${settings.server.fingerprint} " +
                "flow=${settings.server.flow} network=${settings.server.network} " +
                    "reality=${settings.server.isReality}",
        )
        val resolved = UnderlyingDns.resolve(this, host)
        if (resolved.ip != null) {
            dbg("[DNS] event=pre-resolve host=$host ip=${resolved.ip}")
        } else {
            dbg(
                "[DNS] event=pre-resolve host=$host ip=miss" +
                    (resolved.error?.let { " error=$it" } ?: ""),
            )
        }
        if (settings.server.isReality) {
            dbg(
                "[VPN] event=reality dest=${settings.server.serverName} " +
                    "fp=${settings.server.fingerprint} sid=${settings.server.shortId.isNotBlank()}",
            )
        }
        if (stopping) return
        val prepared = prepareLists()
        if (stopping) return
        val generated = container.repository.buildConfig(
            resolved.ip,
            prepared.ruleSets,
            remoteRuleSetFallback = false,
            adBlockRuleSetPath = prepared.adBlockPath,
        )
        if (stopping) return
        if (!generated.isSuccess) {
            fail(generated.error ?: getString(R.string.error_config))
            return
        }
        container.diagnostics.lastStartedConfig = LogSanitizer.sanitize(generated.json)
        try {
            runCatching { BoxRuntime.checkConfig(generated.json) }
                .onSuccess { dbg("[CFG] event=check-config ok bytes=${generated.json.length}") }
                .onFailure { error ->
                    fail(error.message ?: getString(R.string.error_config_singbox))
                    return
                }
            if (stopping) return
            box = ensureBox()
            dbg("[VPN] event=start-or-reload")
            box?.startOrReload(generated.json)
            if (stopping) return
            startedElapsed = SystemClock.elapsedRealtime()
            lastIdleRecoverElapsed = startedElapsed
            lastScreenOffElapsed = 0L
            registerIdleRecovery()
            updateStatus(VpnConnectionState.CONNECTED, getString(R.string.status_connected))
            dbg("[VPN] event=core-started")
            if (settings.autoSelectServerEnabled && AutoServerPolicy.canEnable(settings.servers.size)) {
                container.autoServerSelector.startMonitoring()
            } else if (settings.autoSelectServerEnabled) {
                dbg("[AUTO] event=monitor action=skip reason=too-few-servers count=${settings.servers.size}")
            }
            container.connectionPing.measureAfterConnected("connected")
            tryPromoteRuleSetsViaProxy(resolved.ip, prepared)
        } catch (error: Exception) {
            fail(combineErrors(error))
        }
    }

    private suspend fun reloadInternal() = lifecycleMutex.withLock { reloadInternalLocked() }

    private suspend fun reloadInternalLocked() {
        if (stopping) return
        if (box == null) {
            dbg("[VPN] event=reload action=start-vpn reason=no-command-server")
            startVpnLocked()
            return
        }
        val settings = container.repository.currentSnapshot().settings
        if (stopping) return
        serverName = settings.activeConnectionName()
        dbg("[VPN] event=reload server=$serverName")
        updateStatus(VpnConnectionState.RECONNECTING, getString(R.string.status_reconnecting))
        val host = settings.server.address
        val resolved = UnderlyingDns.resolve(this, host)
        val prepared = prepareLists()
        val wantRemoteLists = settings.automaticRuleSetEnabled &&
            prepared.ruleSets.size < RuleSetCatalog.vpnLists.size
        val generated = container.repository.buildConfig(
            resolved.ip,
            prepared.ruleSets,
            remoteRuleSetFallback = wantRemoteLists,
            adBlockRuleSetPath = prepared.adBlockPath,
        )
        if (!generated.isSuccess) {
            fail(generated.error ?: getString(R.string.error_config))
            return
        }
        try {
            runCatching { BoxRuntime.checkConfig(generated.json) }.onFailure { error ->
                    fail(error.message ?: getString(R.string.error_config_singbox))
                return
            }
            if (stopping) return
            box?.startOrReload(generated.json)
            if (stopping) return
            startedElapsed = SystemClock.elapsedRealtime()
            lastIdleRecoverElapsed = startedElapsed
            lastScreenOffElapsed = 0L
            registerIdleRecovery()
            updateStatus(VpnConnectionState.CONNECTED, getString(R.string.status_connected))
            dbg("[VPN] event=reload ok")
            container.diagnostics.lastStartedConfig = LogSanitizer.sanitize(generated.json)
            container.connectionPing.measureAfterConnected("reconnect")
            // Idle/settings reload already includes remote lists when local
            // copies are missing. A second startOrReloadService here tore TUN
            // down again and Telegram's reconnect landed on a dying stack.
        } catch (error: Exception) {
            fail(combineErrors(error))
        }
    }

    private suspend fun tryPromoteRuleSetsViaProxy(
        resolvedIp: String?,
        prepared: PreparedLists,
    ) {
        if (stopping) return
        val enabled = container.repository.currentSnapshot().settings.automaticRuleSetEnabled
        if (stopping || !enabled || prepared.ruleSets.size >= RuleSetCatalog.vpnLists.size) return
        val withRemote = container.repository.buildConfig(
            resolvedIp,
            prepared.ruleSets,
            remoteRuleSetFallback = true,
            adBlockRuleSetPath = prepared.adBlockPath,
        )
        if (!withRemote.isSuccess) return
        if (runCatching { BoxRuntime.checkConfig(withRemote.json) }.isFailure) return

        val promoted = runCatching {
            box?.startOrReload(withRemote.json)
        }
        if (promoted.isSuccess) {
            dbg("[RULE] event=promote action=ok via=proxy")
            return
        }
        dbg("[RULE] event=promote action=fail via=proxy error=${promoted.exceptionOrNull()?.message}")

        val withoutRemote = container.repository.buildConfig(
            resolvedIp,
            prepared.ruleSets,
            remoteRuleSetFallback = false,
            adBlockRuleSetPath = prepared.adBlockPath,
        )
        val reverted = runCatching {
            check(withoutRemote.isSuccess) { withoutRemote.error ?: getString(R.string.error_config) }
            BoxRuntime.checkConfig(withoutRemote.json)
            box?.startOrReload(withoutRemote.json)
        }
        if (reverted.isFailure) {
            dbg(
                "[RULE] event=promote action=revert-fail " +
                    "error=${reverted.exceptionOrNull()?.message} keep=running-config",
            )
            return
        }
        dbg("[RULE] event=promote action=reverted reason=remote-lists-failed")
    }

    private fun ensureBox(): LibboxSession {
        box?.let { return it }
        return LibboxSession(
            vpn = this,
            host = this,
            diagnostics = container.diagnostics,
            commands = this,
        ).also { box = it }
    }

    private fun releaseBox() {
        runCatching { box?.close() }
        box = null
    }

    private fun stopVpn() {
        if (stopping) return
        stopping = true
        dbg("[VPN] event=stop")
        container.autoServerSelector.stopMonitoring()
        container.connectionPing.clear()
        unregisterIdleRecovery()
        VpnStatusStore.clearTraffic()
        VpnStatusStore.update(VpnUiStatus(VpnConnectionState.DISCONNECTED, getString(R.string.status_disconnected_short)))
        releaseBox()
        startedElapsed = 0L
        lastIdleRecoverElapsed = 0L
        lastHandoffWakeElapsed = 0L
        lastScreenOffElapsed = 0L
        notification.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private data class PreparedLists(
        val ruleSets: Map<String, String>,
        val adBlockPath: String?,
    )

    private suspend fun prepareLists(): PreparedLists {
        val settings = container.repository.currentSnapshot().settings
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val network = UnderlyingDns.pickUnderlyingNetwork(connectivity)
        dbg(
            "[RULE] event=prepare ads=${settings.adBlockEnabled} " +
                "auto=${settings.automaticRuleSetEnabled}",
        )
        return supervisorScope {
            val ruleSets = async {
                if (!settings.automaticRuleSetEnabled) {
                    dbg("[RULE] event=skip reason=disabled")
                    emptyMap()
                } else {
                    fetchRuleSets(network)
                }
            }
            val ads = async {
                if (!settings.adBlockEnabled) {
                    dbg("[ADS] event=skip reason=disabled")
                    null
                } else {
                    fetchAdBlock(network)
                }
            }
            PreparedLists(ruleSets.await(), ads.await())
        }
    }

    private suspend fun fetchRuleSets(network: android.net.Network?): Map<String, String> {
        val downloader = RuleSetDownloader(this)
        val fetched = downloader.ensureDirectCopies(network)
        val local = fetched.files
        val total = RuleSetCatalog.vpnLists.size
        val missing = total - local.size
        dbg(
            when {
                fetched.seededFromAssets > 0 && local.size == total ->
                    "[RULE] event=fetch source=apk release=${RuleSetCatalog.BUNDLED_RELEASE} " +
                        "count=$total github=miss"
                local.size == total ->
                    "[RULE] event=fetch source=direct count=${local.size}"
                local.isEmpty() ->
                    "[RULE] event=fetch source=none count=0" +
                        (fetched.error?.let { " error=$it" } ?: "")
                fetched.seededFromAssets > 0 ->
                    "[RULE] event=fetch source=mixed count=${local.size} " +
                        "fromApk=${fetched.seededFromAssets} missing=$missing" +
                        (fetched.error?.let { " error=$it" } ?: "")
                else ->
                    "[RULE] event=fetch source=partial count=${local.size} missing=$missing" +
                        (fetched.error?.let { " error=$it" } ?: "")
            },
        )
        return local
    }

    private suspend fun fetchAdBlock(network: android.net.Network?): String? {
        val fetched = container.adBlockDownloader.ensureCopy(network)
        dbg(AdBlockDownloader.logLine(fetched))
        return fetched.path
    }

    private fun combineErrors(error: Exception): String {
        return listOfNotNull(error.message)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
            .ifBlank { error.toString() }
    }

    private fun fail(raw: String?) {
        if (stopping) return
        val mapped = ErrorMapper.map(raw)
        val sanitizedRaw = LogSanitizer.sanitize(raw)
        dbg(
            "[VPN] event=fail kind=${mapped.kind} " +
                "raw=${sanitizedRaw.ifBlank { "-" }}",
        )
        stopping = true
        container.autoServerSelector.stopMonitoring()
        container.connectionPing.clear()
        unregisterIdleRecovery()
        VpnStatusStore.clearTraffic()
        VpnStatusStore.update(
            VpnUiStatus(
                state = VpnConnectionState.ERROR,
                message = getString(R.string.error_generic),
                errorTitle = mapped.title,
                errorDetails = mapped.details,
            ),
        )
        releaseBox()
        startedElapsed = 0L
        lastIdleRecoverElapsed = 0L
        lastHandoffWakeElapsed = 0L
        lastScreenOffElapsed = 0L
        notification.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun notifyAutoServerTransport(capabilities: NetworkCapabilities) {
        container.autoServerSelector.onNetworkTransportChanged(
            AutoServerSelector.transportKind(capabilities),
        )
    }

    fun recoverAfterIdle(reason: String, longIdleReload: Boolean = false) {
        if (stopping || box == null) {
            dbg(
                "[VPN] event=recover path=idle action=skip reason=$reason " +
                    "why=${if (stopping) "stopping" else "no-command-server"}",
            )
            return
        }
        val now = SystemClock.elapsedRealtime()
        val connectivity = getSystemService(ConnectivityManager::class.java)
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
        runCatching { box?.wake() }
    }

    override fun recoverAfterHandoff(reason: String) {
        if (stopping || box == null) {
            dbg(
                "[VPN] event=recover path=handoff action=skip reason=$reason " +
                    "why=${if (stopping) "stopping" else "no-command-server"}",
            )
            return
        }
        val now = SystemClock.elapsedRealtime()
        val connectivity = getSystemService(ConnectivityManager::class.java)
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
        runCatching { box?.wake() }
    }

    private fun registerIdleRecovery() {
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
                        container.autoServerSelector.onDeviceBecameInteractive()
                    }
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                        val idle = getSystemService(PowerManager::class.java).isDeviceIdleMode
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
            this,
            screenReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive != false
        if (!interactive) startIdlePoke()
    }

    private fun startIdlePoke() {
        if (stopping || box == null) return
        if (idlePokeJob?.isActive == true) return
        dbg("[VPN] event=idle-poke action=start intervalMs=${IdleRecoveryPolicy.idlePokeMs}")
        idlePokeJob = scope.launch {
            while (isActive) {
                delay(IdleRecoveryPolicy.idlePokeMs)
                if (stopping || box == null) return@launch
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

    private fun unregisterIdleRecovery() {
        stopIdlePoke()
        val receiver = screenReceiver ?: return
        screenReceiver = null
        runCatching { unregisterReceiver(receiver) }
    }

    private fun updateStatus(state: VpnConnectionState, message: String) {
        if (stopping) return
        VpnStatusStore.update(VpnUiStatus(state = state, message = message))
        when (state) {
            VpnConnectionState.CONNECTED,
            VpnConnectionState.CONNECTING,
            VpnConnectionState.RECONNECTING,
            -> notification.update(state, serverName.ifBlank { null })
            else -> Unit
        }
    }

    override fun onTunDump(dump: String) {
        container.diagnostics.lastTunDump = dump
    }

    override fun dbg(message: String) {
        container.diagnostics.append(message)
    }

    override fun onBoxReload() {
        scope.launch { reloadInternal() }
    }

    override fun onBoxStop() {
        stopVpn()
    }

    companion object {
        const val ACTION_START = "com.layer.app.START"
        const val ACTION_STOP = "com.layer.app.STOP"
        const val ACTION_RELOAD = "com.layer.app.RELOAD"
        const val ACTION_REWIRE = "com.layer.app.REWIRE"
        const val EXTRA_SERVER_NAME = "com.layer.app.EXTRA_SERVER_NAME"

        @Volatile
        private var running: LayerVpnService? = null

        fun wakeAfterHandoff(reason: String) {
            running?.recoverAfterHandoff(reason)
        }

        fun start(context: Context, serverName: String = "") {
            val intent = Intent(context, LayerVpnService::class.java).setAction(ACTION_START)
            if (serverName.isNotBlank()) {
                intent.putExtra(EXTRA_SERVER_NAME, serverName)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(stopIntent(context))
        }

        fun reload(context: Context) {
            val intent = Intent(context, LayerVpnService::class.java).setAction(ACTION_RELOAD)
            context.startForegroundService(intent)
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, LayerVpnService::class.java).setAction(ACTION_STOP)
        }

        fun rewireIntent(context: Context): Intent {
            return Intent(context, LayerVpnService::class.java).setAction(ACTION_REWIRE)
        }
    }
}
