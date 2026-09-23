package com.layer.app.vpn

import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import com.layer.app.LayerApp
import com.layer.app.R
import com.layer.app.box.BoxCommandCallbacks
import com.layer.app.box.BoxHost
import com.layer.app.box.BoxRuntime
import com.layer.app.box.libbox.LibboxSession
import com.layer.app.data.AdBlockDownloader
import com.layer.app.data.RuleSetDownloader
import com.layer.core.config.AutoServerPolicy
import com.layer.core.config.RuleSetCatalog
import com.layer.core.diagnostics.ErrorMapper
import com.layer.core.diagnostics.LogSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SFA-style BoxService: owns start/reload/stop and the libbox session.
 * [LayerVpnService] is the Android VpnService shell.
 *
 * Start and stop both take [lifecycleMutex]. A boolean `stopping` flag used
 * to be cleared by the next START, which let always-on overlap teardown.
 * [VpnPhase] makes that overlap a no-op until the phase is Stopped or Failed.
 */
internal class LayerBoxService(
    private val vpn: VpnService,
) : BoxHost, BoxCommandCallbacks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val notification by lazy { VpnNotification(vpn) }

    @Volatile
    var phase: VpnPhase = VpnPhase.Stopped
        private set

    private var box: LibboxSession? = null
    private var serverName: String = ""
    private val idle = IdleRecovery(
        context = vpn,
        scope = scope,
        isLive = { phase.isSessionLive() && box != null },
        wake = { runCatching { box?.wake() } },
        onInteractive = { container.autoServerSelector.onDeviceBecameInteractive() },
        dbg = ::dbg,
    )

    private val container get() = (vpn.application as LayerApp).container

    fun onStartCommand(intent: Intent?): Int {
        if (intent?.action != LayerVpnService.ACTION_STOP) {
            intent?.getStringExtra(LayerVpnService.EXTRA_SERVER_NAME)?.takeIf { it.isNotBlank() }?.let {
                serverName = it
            }
            notification.startForeground(phase.toUi(), serverName.ifBlank { null })
        }
        return when (intent?.action) {
            LayerVpnService.ACTION_STOP -> {
                requestStop()
                Service.START_NOT_STICKY
            }
            LayerVpnService.ACTION_RELOAD, LayerVpnService.ACTION_REWIRE -> {
                if (intent.action == LayerVpnService.ACTION_REWIRE) {
                    dbg("[VPN] event=rewire source=notification")
                }
                scope.launch { reload() }
                Service.START_STICKY
            }
            else -> {
                // START, Always-on VPN, or reboot: system may pass a null action.
                if (phase.isSessionLive() && box != null) {
                    dbg("[VPN] event=start action=skip reason=already-running state=${phase.toUi()}")
                    return Service.START_STICKY
                }
                if (phase == VpnPhase.Stopping) {
                    dbg("[VPN] event=start action=skip reason=stopping")
                    return Service.START_STICKY
                }
                phase = VpnPhase.Starting
                scope.launch { start() }
                Service.START_STICKY
            }
        }
    }

    fun onTaskRemoved() {
        if (phase.isSessionLive()) {
            notification.startForeground(phase.toUi(), serverName.ifBlank { null })
        }
    }

    fun onDestroy() {
        // Stop is synchronous so the box closes before this scope is cancelled.
        requestStop()
        scope.cancel()
    }

    fun onRevoke() {
        requestStop()
    }

    override fun recoverAfterHandoff(reason: String) = idle.recoverAfterHandoff(reason)

    private suspend fun start() = lifecycleMutex.withLock { startLocked() }

    private suspend fun reload() = lifecycleMutex.withLock { reloadLocked() }

    fun requestStop() {
        if (phase == VpnPhase.Stopped || phase == VpnPhase.Stopping || phase == VpnPhase.Failed) {
            return
        }
        phase = VpnPhase.Stopping
        teardownLocked(failed = false)
    }

    private suspend fun startLocked() {
        if (phase != VpnPhase.Starting) return
        if (box != null && phase.isSessionLive()) {
            dbg("[VPN] event=start action=skip reason=already-running state=${phase.toUi()}")
            return
        }
        var settings = container.repository.currentSnapshot().settings
        if (phase != VpnPhase.Starting) return
        val autoPick = settings.autoSelectServerEnabled &&
            AutoServerPolicy.canEnable(settings.servers.size)
        dbg(
            "[AUTO] event=startVpn auto=${settings.autoSelectServerEnabled} " +
                "servers=${settings.servers.size} pick=$autoPick",
        )
        updateStatus(
            VpnConnectionState.CONNECTING,
            if (autoPick) vpn.getString(R.string.status_checking_servers)
            else vpn.getString(R.string.status_connecting_ellipsis),
        )
        if (autoPick) {
            container.autoServerSelector.prepareBestServer()
            settings = container.repository.currentSnapshot().settings
            dbg("[AUTO] event=startVpn action=after-pick server=${settings.activeConnectionName()}")
        }
        if (phase != VpnPhase.Starting) return
        serverName = settings.activeConnectionName()
        val host = settings.server.address
        dbg(
            "[VPN] event=start server=${settings.activeConnectionName()} " +
                "host=$host:${settings.server.port} sni=${settings.server.serverName} " +
                "security=${settings.server.security} fp=${settings.server.fingerprint} " +
                "flow=${settings.server.flow} network=${settings.server.network} " +
                "reality=${settings.server.isReality}",
        )
        val resolved = UnderlyingDns.resolve(vpn, host)
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
        if (phase != VpnPhase.Starting) return
        val prepared = prepareLists()
        if (phase != VpnPhase.Starting) return
        val generated = container.repository.buildConfig(
            resolved.ip,
            prepared.ruleSets,
            remoteRuleSetFallback = false,
            adBlockRuleSetPath = prepared.adBlockPath,
        )
        if (phase != VpnPhase.Starting) return
        if (!generated.isSuccess) {
            failLocked(generated.error ?: vpn.getString(R.string.error_config))
            return
        }
        container.diagnostics.lastStartedConfig = LogSanitizer.sanitize(generated.json)
        try {
            runCatching { BoxRuntime.checkConfig(generated.json) }
                .onSuccess { dbg("[CFG] event=check-config ok bytes=${generated.json.length}") }
                .onFailure { error ->
                    failLocked(error.message ?: vpn.getString(R.string.error_config_singbox))
                    return
                }
            if (phase != VpnPhase.Starting) return
            box = ensureBox()
            dbg("[VPN] event=start-or-reload")
            box?.startOrReload(generated.json)
            if (phase != VpnPhase.Starting) return
            idle.markStarted()
            idle.register()
            phase = VpnPhase.Started
            updateStatus(VpnConnectionState.CONNECTED, vpn.getString(R.string.status_connected))
            dbg("[VPN] event=core-started")
            if (settings.autoSelectServerEnabled && AutoServerPolicy.canEnable(settings.servers.size)) {
                container.autoServerSelector.startMonitoring()
            } else if (settings.autoSelectServerEnabled) {
                dbg("[AUTO] event=monitor action=skip reason=too-few-servers count=${settings.servers.size}")
            }
            container.connectionPing.measureAfterConnected("connected")
            tryPromoteRuleSetsViaProxy(resolved.ip, prepared)
        } catch (error: Exception) {
            failLocked(combineErrors(error))
        }
    }

    private suspend fun reloadLocked() {
        if (phase == VpnPhase.Stopping || phase == VpnPhase.Stopped) return
        if (box == null) {
            dbg("[VPN] event=reload action=start-vpn reason=no-command-server")
            phase = VpnPhase.Starting
            startLocked()
            return
        }
        phase = VpnPhase.Reloading
        val settings = container.repository.currentSnapshot().settings
        if (phase != VpnPhase.Reloading) return
        serverName = settings.activeConnectionName()
        dbg("[VPN] event=reload server=$serverName")
        updateStatus(VpnConnectionState.RECONNECTING, vpn.getString(R.string.status_reconnecting))
        val host = settings.server.address
        val resolved = UnderlyingDns.resolve(vpn, host)
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
            failLocked(generated.error ?: vpn.getString(R.string.error_config))
            return
        }
        try {
            runCatching { BoxRuntime.checkConfig(generated.json) }.onFailure { error ->
                failLocked(error.message ?: vpn.getString(R.string.error_config_singbox))
                return
            }
            if (phase != VpnPhase.Reloading) return
            box?.startOrReload(generated.json)
            if (phase != VpnPhase.Reloading) return
            idle.markStarted()
            idle.register()
            phase = VpnPhase.Started
            updateStatus(VpnConnectionState.CONNECTED, vpn.getString(R.string.status_connected))
            dbg("[VPN] event=reload ok")
            container.diagnostics.lastStartedConfig = LogSanitizer.sanitize(generated.json)
            container.connectionPing.measureAfterConnected("reconnect")
            // Idle/settings reload already includes remote lists when local
            // copies are missing. A second startOrReloadService here tore TUN
            // down again and Telegram's reconnect landed on a dying stack.
        } catch (error: Exception) {
            failLocked(combineErrors(error))
        }
    }

    private suspend fun tryPromoteRuleSetsViaProxy(
        resolvedIp: String?,
        prepared: PreparedLists,
    ) {
        if (phase != VpnPhase.Started) return
        val enabled = container.repository.currentSnapshot().settings.automaticRuleSetEnabled
        if (phase != VpnPhase.Started || !enabled || prepared.ruleSets.size >= RuleSetCatalog.vpnLists.size) return
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
            check(withoutRemote.isSuccess) { withoutRemote.error ?: vpn.getString(R.string.error_config) }
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
            vpn = vpn,
            host = this,
            diagnostics = container.diagnostics,
            commands = this,
        ).also { box = it }
    }

    private fun teardownLocked(failed: Boolean, raw: String? = null) {
        container.autoServerSelector.stopMonitoring()
        container.connectionPing.clear()
        idle.unregister()
        idle.clear()
        VpnStatusStore.clearTraffic()
        if (failed) {
            val mapped = ErrorMapper.map(raw)
            val sanitizedRaw = LogSanitizer.sanitize(raw)
            dbg(
                "[VPN] event=fail kind=${mapped.kind} " +
                    "raw=${sanitizedRaw.ifBlank { "-" }}",
            )
            phase = VpnPhase.Failed
            VpnStatusStore.update(
                VpnUiStatus(
                    state = VpnConnectionState.ERROR,
                    message = vpn.getString(R.string.error_generic),
                    errorTitle = mapped.title,
                    errorDetails = mapped.details,
                ),
            )
        } else {
            dbg("[VPN] event=stop")
            phase = VpnPhase.Stopped
            VpnStatusStore.update(
                VpnUiStatus(
                    VpnConnectionState.DISCONNECTED,
                    vpn.getString(R.string.status_disconnected_short),
                ),
            )
        }
        runCatching { box?.close() }
        box = null
        notification.cancel()
        vpn.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        vpn.stopSelf()
    }

    private fun failLocked(raw: String?) {
        if (phase == VpnPhase.Stopping || phase == VpnPhase.Failed) return
        teardownLocked(failed = true, raw = raw)
    }

    private data class PreparedLists(
        val ruleSets: Map<String, String>,
        val adBlockPath: String?,
    )

    private suspend fun prepareLists(): PreparedLists {
        val snapshot = container.repository.currentSnapshot()
        val settings = snapshot.settings
        val connectivity = vpn.getSystemService(ConnectivityManager::class.java)
        val network = UnderlyingDns.pickUnderlyingNetwork(connectivity)
        dbg(
            "[RULE] event=prepare ads=${snapshot.adBlockApps.size} " +
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
                if (snapshot.adBlockApps.isEmpty()) {
                    dbg("[ADS] event=skip reason=no-apps")
                    null
                } else {
                    fetchAdBlock(network)
                }
            }
            PreparedLists(ruleSets.await(), ads.await())
        }
    }

    private suspend fun fetchRuleSets(network: android.net.Network?): Map<String, String> {
        val downloader = RuleSetDownloader(vpn)
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

    private fun updateStatus(state: VpnConnectionState, message: String) {
        if (phase == VpnPhase.Stopping || phase == VpnPhase.Failed) return
        VpnStatusStore.update(VpnUiStatus(state = state, message = message))
        when (state) {
            VpnConnectionState.CONNECTED,
            VpnConnectionState.CONNECTING,
            VpnConnectionState.RECONNECTING,
            -> notification.update(state, serverName.ifBlank { null })
            else -> Unit
        }
    }

    override fun notifyAutoServerTransport(capabilities: NetworkCapabilities) {
        container.autoServerSelector.onNetworkTransportChanged(
            AutoServerSelector.transportKind(capabilities),
        )
    }

    override fun onTunDump(dump: String) {
        container.diagnostics.lastTunDump = dump
    }

    override fun dbg(message: String) {
        container.diagnostics.append(message)
    }

    override fun onBoxReload() {
        scope.launch { reload() }
    }

    override fun onBoxStop() {
        requestStop()
    }
}
