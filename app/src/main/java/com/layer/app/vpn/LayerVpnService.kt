package com.layer.app.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.layer.app.LayerApp
import com.layer.app.R
import com.layer.app.data.AdBlockDownloader
import com.layer.app.data.RuleSetDownloader
import com.layer.core.config.RuleSetCatalog
import com.layer.core.config.AutoServerPolicy
import com.layer.core.config.IdleRecoveryPolicy
import com.layer.core.config.TunDirectExcludePolicy
import com.layer.core.model.AppRoutingMode
import com.layer.core.diagnostics.Branding
import com.layer.core.diagnostics.ErrorMapper
import com.layer.core.diagnostics.LogSanitizer
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
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
import java.net.InetAddress

class LayerVpnService : VpnService(), CommandServerHandler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val runtimeLock = Any()
    private val notification by lazy { VpnNotification(this) }
    // Stop runs on the main thread while start/reload run on Dispatchers.IO
    // under lifecycleMutex, so both sides must see these writes.
    @Volatile private var vpnFd: ParcelFileDescriptor? = null
    @Volatile private var commandServer: CommandServer? = null
    @Volatile private var logClient: CommandClient? = null
    @Volatile private var platform: SingBoxPlatform? = null

    private val container get() = (application as LayerApp).container
    @Volatile private var stopping = false
    private var runtimeGeneration = 0L
    private var serverName: String = ""
    private var screenReceiver: BroadcastReceiver? = null
    private var startedElapsed = 0L
    private var lastIdleRecoverElapsed = 0L
    private var lastHandoffWakeElapsed = 0L
    private var lastScreenOffElapsed = 0L
    private var idlePokeJob: Job? = null
    private var logClientStatusIntervalNs = 0L
    @Volatile private var logClientVerbose = false
    @Volatile private var logClientReplacing = false
    private var logClientReattachCount = 0
    @Volatile private var lastExcludeDirectFromTun: Boolean? = null
    @Volatile private var lastAppliedExcludePackages: List<String>? = null

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
                val requestGeneration = synchronized(runtimeLock) { runtimeGeneration }
                val state = VpnStatusStore.status.value.state
                if (commandServer != null &&
                    (state == VpnConnectionState.CONNECTED ||
                        state == VpnConnectionState.CONNECTING ||
                        state == VpnConnectionState.RECONNECTING)
                ) {
                    dbg("[VPN] event=start action=skip reason=already-running state=$state")
                    return START_STICKY
                }
                scope.launch { startVpn(requestGeneration) }
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

    private suspend fun startVpn(requestGeneration: Long) = lifecycleMutex.withLock {
        val currentRequest = synchronized(runtimeLock) {
            if (runtimeGeneration != requestGeneration) {
                false
            } else {
                stopping = false
                true
            }
        }
        if (currentRequest) startVpnLocked()
    }

    private suspend fun startVpnLocked() {
        if (stopping) return
        val already = VpnStatusStore.status.value.state
        if (commandServer != null &&
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
        val generated = buildRunningConfig(
            resolved.ip,
            prepared,
            remoteRuleSetFallback = false,
        )
        if (stopping) return
        if (!generated.isSuccess) {
            fail(generated.error ?: getString(R.string.error_config))
            return
        }
        container.diagnostics.lastStartedConfig = LogSanitizer.sanitize(generated.json)
        try {
            runCatching { Libbox.checkConfig(generated.json) }
                .onSuccess { dbg("[CFG] event=check-config ok bytes=${generated.json.length}") }
                .onFailure { error ->
                    fail(error.message ?: getString(R.string.error_config_singbox))
                    return
                }
            if (stopping) return
            commandServer = ensureCommandServer()
            dbg("[VPN] event=start-or-reload")
            commandServer?.startOrReloadService(generated.json, OverrideOptions())
            if (abortIfStopping("start")) return
            logClientVerbose = settings.verboseBoxLogEnabled
            attachLogClient()
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
            if (abortIfStopping("start-error")) return
            fail(combineErrors(error))
        }
    }

    private suspend fun reloadInternal() = lifecycleMutex.withLock { reloadInternalLocked() }

    private suspend fun reloadInternalLocked() {
        if (stopping) return
        if (commandServer == null) {
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
        val generated = buildRunningConfig(
            resolved.ip,
            prepared,
            remoteRuleSetFallback = wantRemoteLists,
        )
        if (!generated.isSuccess) {
            fail(generated.error ?: getString(R.string.error_config))
            return
        }
        try {
            runCatching { Libbox.checkConfig(generated.json) }.onFailure { error ->
                    fail(error.message ?: getString(R.string.error_config_singbox))
                return
            }
            if (stopping) return
            commandServer?.startOrReloadService(generated.json, OverrideOptions())
            if (abortIfStopping("reload")) return
            logClientVerbose = settings.verboseBoxLogEnabled
            attachLogClient()
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
            if (abortIfStopping("reload-error")) return
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
        val withRemote = buildRunningConfig(
            resolvedIp,
            prepared,
            remoteRuleSetFallback = true,
        )
        if (!withRemote.isSuccess) return
        if (runCatching { Libbox.checkConfig(withRemote.json) }.isFailure) return

        val promoted = runCatching {
            commandServer?.startOrReloadService(withRemote.json, OverrideOptions())
        }
        if (abortIfStopping("promote")) return
        if (promoted.isSuccess) {
            dbg("[RULE] event=promote action=ok via=proxy")
            return
        }
        dbg("[RULE] event=promote action=fail via=proxy error=${promoted.exceptionOrNull()?.message}")

        val withoutRemote = buildRunningConfig(
            resolvedIp,
            prepared,
            remoteRuleSetFallback = false,
        )
        val reverted = runCatching {
            check(withoutRemote.isSuccess) { withoutRemote.error ?: getString(R.string.error_config) }
            Libbox.checkConfig(withoutRemote.json)
            commandServer?.startOrReloadService(withoutRemote.json, OverrideOptions())
        }
        if (abortIfStopping("promote-revert")) return
        if (reverted.isFailure) {
            dbg(
                "[RULE] event=promote action=revert-fail " +
                    "error=${reverted.exceptionOrNull()?.message} keep=running-config",
            )
            return
        }
        dbg("[RULE] event=promote action=reverted reason=remote-lists-failed")
    }

    private fun ensureCommandServer(): CommandServer {
        synchronized(runtimeLock) {
            commandServer?.let { return it }
            check(!stopping) { "android: VPN is stopping" }
        }
        val iface = SingBoxPlatform(this)
        dbg("[BOX] event=command-server-start libbox=${Branding.libboxVersion(runCatching { Libbox.version() }.getOrNull())}")
        val server = try {
            CommandServer(this, iface).also { it.start() }
        } catch (error: Exception) {
            iface.close()
            throw error
        }
        val accepted = synchronized(runtimeLock) {
            if (stopping) {
                false
            } else {
                platform = iface
                commandServer = server
                true
            }
        }
        if (!accepted) {
            runCatching { server.closeService() }
            runCatching { server.close() }
            iface.close()
            error("android: VPN stopped while starting command server")
        }
        // The log client is attached only after startOrReloadService. Before
        // the instance exists libbox answers "get default log level: invalid
        // argument" and drops the stream for good.
        return server
    }

    private fun desiredStatusIntervalNs(): Long {
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive != false
        return if (interactive) {
            IdleRecoveryPolicy.statusIntervalInteractiveNs
        } else {
            IdleRecoveryPolicy.statusIntervalIdleNs
        }
    }

    private fun attachLogClient(force: Boolean = true) {
        val interval = desiredStatusIntervalNs()
        val generation = synchronized(runtimeLock) { runtimeGeneration }
        val previous = synchronized(runtimeLock) {
            if (!force && logClient != null && logClientStatusIntervalNs == interval) return
            logClient.also {
                logClient = null
                logClientStatusIntervalNs = 0L
            }
        }
        // Tearing down the old client fires disconnected() on the libbox
        // thread; that reattach must not fight this one.
        logClientReplacing = true
        runCatching { previous?.disconnect() }
        logClientReplacing = false
        val options = CommandClientOptions().apply {
            addCommand(Libbox.CommandLog)
            addCommand(Libbox.CommandStatus)
            statusInterval = interval
        }
        val bridge = SingBoxLogBridge(
            diagnostics = container.diagnostics,
            verbose = logClientVerbose,
            onStreamLost = { reattachLogClient(generation) },
        )
        val client = CommandClient(bridge, options)
        val connected = runCatching { client.connect() }
        if (connected.isFailure) {
            dbg("[BOX] event=log-client action=fail error=${connected.exceptionOrNull()?.message}")
            runCatching { client.connect() }
                .onFailure { dbg("[BOX] event=log-client action=retry-fail error=${it.message}") }
                .onSuccess { dbg("[BOX] event=log-client action=retry-ok intervalNs=$interval") }
        } else {
            dbg("[BOX] event=log-client action=ok intervalNs=$interval verbose=$logClientVerbose")
        }
        val accepted = synchronized(runtimeLock) {
            if (stopping || commandServer == null) {
                false
            } else {
                logClient = client
                logClientStatusIntervalNs = interval
                true
            }
        }
        if (!accepted) {
            logClientReplacing = true
            runCatching { client.disconnect() }
            logClientReplacing = false
            dbg("[BOX] event=log-client action=discard reason=stopping")
        } else {
            logClientReattachCount = 0
        }
    }

    /**
     * libbox drops the command socket on any RPC error and never redials, so
     * a single failure used to cost every sing-box line for the rest of the
     * session. Bounded so a permanently broken socket cannot spin.
     */
    private fun reattachLogClient(generation: Long) {
        if (logClientReplacing) return
        scope.launch {
            delay(LOG_CLIENT_REATTACH_DELAY_MS)
            lifecycleMutex.withLock {
                val proceed = synchronized(runtimeLock) {
                    when {
                        stopping || commandServer == null -> false
                        runtimeGeneration != generation -> false
                        logClientReattachCount >= LOG_CLIENT_REATTACH_MAX -> false
                        else -> {
                            logClientReattachCount += 1
                            true
                        }
                    }
                }
                if (!proceed) return@withLock
                dbg("[BOX] event=log-client action=reattach attempt=$logClientReattachCount")
                attachLogClient()
            }
        }
    }

    private fun retuneLogClient() {
        scope.launch {
            lifecycleMutex.withLock {
                if (stopping || commandServer == null) return@withLock
                attachLogClient(force = false)
            }
        }
    }

    private fun stopVpn() {
        val firstStop = synchronized(runtimeLock) {
            if (stopping) {
                false
            } else {
                stopping = true
                runtimeGeneration += 1
                true
            }
        }
        if (!firstStop) {
            // A previous teardown may have raced a late native callback.
            releaseRuntime()
            return
        }
        dbg("[VPN] event=stop")
        container.autoServerSelector.stopMonitoring()
        container.connectionPing.clear()
        unregisterIdleRecovery()
        VpnStatusStore.clearTraffic()
        VpnStatusStore.update(VpnUiStatus(VpnConnectionState.DISCONNECTED, getString(R.string.status_disconnected_short)))
        releaseRuntime()
        notification.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Idempotent teardown of everything start/reload may have created.
     * Stop happens outside [lifecycleMutex] (onDestroy cancels [scope], so it
     * cannot wait for the lock), therefore an in-flight start can still create
     * a platform / command server after stop tore things down. Those paths call
     * this again once they observe [stopping].
     */
    private fun releaseRuntime() {
        val resources = synchronized(runtimeLock) {
            RuntimeResources(logClient, commandServer, vpnFd, platform).also {
                logClient = null
                logClientStatusIntervalNs = 0L
                logClientReattachCount = 0
                commandServer = null
                vpnFd = null
                platform = null
                startedElapsed = 0L
                lastIdleRecoverElapsed = 0L
                lastHandoffWakeElapsed = 0L
                lastScreenOffElapsed = 0L
                lastExcludeDirectFromTun = null
                lastAppliedExcludePackages = null
            }
        }
        runCatching { resources.logClient?.disconnect() }
        runCatching { resources.commandServer?.closeService() }
        runCatching { resources.commandServer?.close() }
        runCatching { resources.vpnFd?.close() }
        resources.platform?.close()
    }

    private data class RuntimeResources(
        val logClient: CommandClient?,
        val commandServer: CommandServer?,
        val vpnFd: ParcelFileDescriptor?,
        val platform: SingBoxPlatform?,
    )

    private fun abortIfStopping(path: String): Boolean {
        if (!stopping) return false
        if (commandServer != null || platform != null || vpnFd != null || logClient != null) {
            dbg("[VPN] event=stop action=release-late path=$path")
        }
        releaseRuntime()
        return true
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

    private suspend fun buildRunningConfig(
        resolvedIp: String?,
        prepared: PreparedLists,
        remoteRuleSetFallback: Boolean,
    ): com.layer.core.config.ConfigGenerationResult {
        val exclude = excludeDirectFromTun()
        val snap = container.repository.currentSnapshot()
        val decision = TunDirectExcludePolicy.decide(
            excludeDirectFromTun = exclude,
            directApps = snap.appRules
                .filter { it.mode == AppRoutingMode.DIRECT }
                .map { it.packageName },
            vpnApps = snap.appRules
                .filter { it.mode == AppRoutingMode.VPN }
                .map { it.packageName },
            packagesSharingUid = ::packagesSharingUid,
        )
        dbg(
            "[TUN] event=exclude-direct enabled=$exclude " +
                "lockdown=${lockdownEnabled()} sdk=${Build.VERSION.SDK_INT} " +
                "count=${decision.packages.size} skipped=${decision.skipped.size}",
        )
        decision.skipped.forEach { skip ->
            dbg(
                "[TUN] event=exclude-skip pkg=${skip.packageName} " +
                    "reason=${skip.reason} uid=${skip.uidPackages.joinToString()}",
            )
        }
        return container.repository.buildConfig(
            resolvedIp,
            prepared.ruleSets,
            remoteRuleSetFallback = remoteRuleSetFallback,
            adBlockRuleSetPath = prepared.adBlockPath,
            excludeDirectFromTun = exclude,
        )
    }

    private fun lockdownEnabled(): Boolean {
        return runCatching { isLockdownEnabled() }.getOrDefault(true)
    }

    private fun excludeDirectFromTun(): Boolean = !lockdownEnabled()

    private fun packagesSharingUid(packageName: String): List<String>? {
        return runCatching {
            val uid = packageManager.getPackageUid(
                packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
            packageManager.getPackagesForUid(uid)?.toList()
        }.getOrNull()
    }

    fun onInstalledPackagesChanged() {
        recheckTunExclusions("package-changed")
    }

    private fun recheckTunExclusions(reason: String) {
        if (stopping || commandServer == null) return
        scope.launch { recheckTunExclusionsLocked(reason) }
    }

    private suspend fun recheckTunExclusionsLocked(reason: String) {
        lifecycleMutex.withLock {
            if (stopping || commandServer == null) return
            val appliedMode = lastExcludeDirectFromTun ?: return
            val appliedPackages = lastAppliedExcludePackages ?: return
            val wantMode = excludeDirectFromTun()
            val snap = container.repository.currentSnapshot()
            val wantPackages = if (!wantMode) {
                emptyList()
            } else {
                TunDirectExcludePolicy.packages(
                    excludeDirectFromTun = true,
                    directApps = snap.appRules
                        .filter { it.mode == AppRoutingMode.DIRECT }
                        .map { it.packageName },
                    vpnApps = snap.appRules
                        .filter { it.mode == AppRoutingMode.VPN }
                        .map { it.packageName },
                    packagesSharingUid = ::packagesSharingUid,
                )
            }
            if (appliedMode == wantMode && appliedPackages.toSet() == wantPackages.toSet()) return
            dbg(
                "[VPN] event=reload path=tun-exclude reason=$reason " +
                    "mode=$appliedMode->$wantMode " +
                    "packages=${appliedPackages.size}->${wantPackages.size}",
            )
            reloadInternalLocked()
        }
    }

    private fun fail(raw: String?) {
        val firstFailure = synchronized(runtimeLock) {
            if (stopping) {
                false
            } else {
                stopping = true
                runtimeGeneration += 1
                true
            }
        }
        if (!firstFailure) {
            releaseRuntime()
            return
        }
        val mapped = ErrorMapper.map(raw)
        val sanitizedRaw = LogSanitizer.sanitize(raw)
        dbg(
            "[VPN] event=fail kind=${mapped.kind} " +
                "raw=${sanitizedRaw.ifBlank { "-" }}",
        )
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
        releaseRuntime()
        notification.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun notifyAutoServerTransport(caps: NetworkCapabilities) {
        container.autoServerSelector.onNetworkTransportChanged(
            AutoServerSelector.transportKind(caps),
        )
    }

    fun recoverAfterIdle(reason: String, longIdleReload: Boolean = false) {
        if (stopping || commandServer == null) {
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
        runCatching { commandServer?.wake() }
    }

    fun recoverAfterHandoff(reason: String) {
        if (stopping || commandServer == null) {
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
        runCatching { commandServer?.wake() }
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
                        retuneLogClient()
                    }
                    Intent.ACTION_SCREEN_ON,
                    Intent.ACTION_USER_PRESENT,
                    -> {
                        dbg(
                            "[VPN] event=screen action=" +
                                if (intent.action == Intent.ACTION_USER_PRESENT) "user-present" else "on",
                        )
                        stopIdlePoke()
                        retuneLogClient()
                        recoverAfterIdle(
                            if (intent.action == Intent.ACTION_USER_PRESENT) "user-present" else "screen-on",
                            longIdleReload = true,
                        )
                        container.autoServerSelector.onDeviceBecameInteractive()
                        recheckTunExclusions(
                            if (intent.action == Intent.ACTION_USER_PRESENT) "user-present" else "screen-on",
                        )
                    }
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                        val idle = getSystemService(PowerManager::class.java).isDeviceIdleMode
                        dbg("[VPN] event=idle-mode on=$idle")
                        if (idle) {
                            if (lastScreenOffElapsed == 0L) {
                                lastScreenOffElapsed = SystemClock.elapsedRealtime()
                            }
                            stopIdlePoke()
                        } else {
                            recoverAfterIdle("idle-mode-off", longIdleReload = true)
                            val interactive = getSystemService(PowerManager::class.java)
                                ?.isInteractive != false
                            if (!interactive) startIdlePoke()
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
        if (stopping || commandServer == null) return
        if (getSystemService(PowerManager::class.java)?.isDeviceIdleMode == true) return
        if (idlePokeJob?.isActive == true) return
        dbg("[VPN] event=idle-poke action=start intervalMs=${IdleRecoveryPolicy.idlePokeMs}")
        idlePokeJob = scope.launch {
            while (isActive) {
                delay(IdleRecoveryPolicy.idlePokeMs)
                if (stopping || commandServer == null) return@launch
                val doze = getSystemService(PowerManager::class.java)?.isDeviceIdleMode == true
                val age = VpnStatusStore.trafficAgeMs()
                if (IdleRecoveryPolicy.idlePokeSkipReason(doze, age) != null) continue
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

    fun openTun(options: TunOptions): Int {
        if (stopping) error("android: VPN is stopping")
        if (prepare(this) != null) error("android: missing vpn permission")
        val dump = StringBuilder()
        fun note(line: String) {
            dump.appendLine(line)
            dbg(line)
        }
        val builder = Builder()
            .setSession("Layer")
            .setMtu(options.mtu)
            .setMetered(false)
        note(
            "[TUN] mtu=${options.mtu} autoRoute=${options.autoRoute} " +
                "strict=${options.strictRoute} dnsMode=${options.dnsMode?.value}",
        )
        val inet4 = drainPrefixes(options.inet4Address)
        val inet6 = drainPrefixes(options.inet6Address)
        inet4.forEach { (address, prefix) ->
            builder.addAddress(address, prefix)
            note("[TUN] address $address/$prefix")
        }
        inet6.forEach { (address, prefix) ->
            builder.addAddress(address, prefix)
            note("[TUN] address6 $address/$prefix")
        }
        var appliedExcludeMode = false
        var appliedExcludePackages = emptyList<String>()
        if (options.autoRoute) {
            val dnsServers = mutableListOf<String>()
            val dnsResult = runCatching {
                drainStrings(options.dnsServerAddress).also { dnsServers += it }
            }
            if (dnsResult.isFailure) {
                note("[TUN] dnsServerAddress error: ${dnsResult.exceptionOrNull()?.message}")
            }
            if (dnsServers.isEmpty()) {
                dnsServers += listOf("1.1.1.1", "8.8.8.8")
                note("[TUN] dns fallback=1.1.1.1,8.8.8.8 reason=libbox-empty")
            }
            dnsServers.forEach { server ->
                builder.addDnsServer(server)
                note("[TUN] dns $server")
            }
            val inet4Routes = drainPrefixes(options.inet4RouteAddress)
            if (inet4Routes.isNotEmpty()) {
                inet4Routes.forEach { (address, prefix) ->
                    builder.addRoute(address, prefix)
                    note("[TUN] route $address/$prefix")
                }
            } else {
                builder.addRoute("0.0.0.0", 0)
                note("[TUN] route 0.0.0.0/0 fallback reason=inet4RouteAddress-empty")
            }
            val inet6Routes = drainPrefixes(options.inet6RouteAddress)
            if (inet6Routes.isNotEmpty()) {
                inet6Routes.forEach { (address, prefix) ->
                    builder.addRoute(address, prefix)
                    note("[TUN] route6 $address/$prefix")
                }
            } else if (inet6.isNotEmpty()) {
                builder.addRoute("::", 0)
                note("[TUN] route ::/0")
            }
            drainPrefixes(options.inet4RouteExcludeAddress).forEach { (address, prefix) ->
                builder.excludeRoute(IpPrefix(InetAddress.getByName(address), prefix))
                note("[TUN] exclude $address/$prefix")
            }
            drainPrefixes(options.inet6RouteExcludeAddress).forEach { (address, prefix) ->
                builder.excludeRoute(IpPrefix(InetAddress.getByName(address), prefix))
                note("[TUN] exclude6 $address/$prefix")
            }
            drainStrings(options.includePackage).forEach { pkg ->
                runCatching { builder.addAllowedApplication(pkg) }
                    .onSuccess { note("[TUN] allow $pkg") }
                    .onFailure { note("[TUN] allow $pkg failed: ${it.message}") }
            }
            val skipDirectExclude = lockdownEnabled()
            val excludeList = drainStrings(options.excludePackage)
            // Only packages the builder accepted count as applied, so a failed
            // disallow is retried by the next recheck instead of looking done.
            val applied = mutableListOf<String>()
            excludeList.forEach { pkg ->
                if (skipDirectExclude) {
                    note("[TUN] disallow $pkg skipped reason=lockdown")
                    return@forEach
                }
                runCatching { builder.addDisallowedApplication(pkg) }
                    .onSuccess {
                        applied += pkg
                        note("[TUN] disallow $pkg")
                    }
                    .onFailure { note("[TUN] disallow $pkg failed: ${it.message}") }
            }
            appliedExcludeMode = !skipDirectExclude
            appliedExcludePackages = applied.toList()
        }
        runCatching { builder.addDisallowedApplication(packageName) }
            .onSuccess { note("[TUN] disallow self $packageName") }
            .onFailure { note("[TUN] disallow self failed: ${it.message}") }
        // Close the old PFD before establish(). The next establish() already
        // invalidates it; reading previous.fd afterwards throws "Already closed".
        val previous = vpnFd
        vpnFd = null
        if (previous != null) {
            runCatching { previous.close() }
                .onSuccess { note("[TUN] closed previous") }
                .onFailure { note("[TUN] previous close: ${it.message}") }
        }
        if (stopping) error("android: VPN is stopping")
        val pfd = builder.establish() ?: error("android: the application is not prepared or is revoked")
        val accepted = synchronized(runtimeLock) {
            if (stopping) {
                false
            } else {
                vpnFd = pfd
                if (options.autoRoute) {
                    lastExcludeDirectFromTun = appliedExcludeMode
                    lastAppliedExcludePackages = appliedExcludePackages
                }
                true
            }
        }
        if (!accepted) {
            runCatching { pfd.close() }
            error("android: VPN stopped while establishing TUN")
        }
        note("[TUN] establish ok fd=${pfd.fd}")
        container.diagnostics.lastTunDump = dump.toString().trim()
        return pfd.fd
    }

    fun onLibboxLog(message: String) {
        val clean = LogSanitizer.sanitize(message)
        if (clean.isNotBlank()) dbg("[BOX] $clean")
    }

    fun dbg(message: String) {
        container.diagnostics.append(message)
    }

    override fun serviceReload() {
        scope.launch { reloadInternal() }
    }

    override fun serviceStop() {
        stopVpn()
    }

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()

    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit

    override fun triggerNativeCrash() = Unit

    override fun writeDebugMessage(message: String?) {
        if (!message.isNullOrBlank()) dbg("[BOX] $message")
    }

    override fun connectSSHAgent(): Int = -1

    private fun drainPrefixes(iterator: RoutePrefixIterator): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        while (iterator.hasNext()) {
            val prefix = iterator.next() ?: continue
            out += prefix.address() to prefix.prefix()
        }
        return out
    }

    private fun drainStrings(iterator: StringIterator): List<String> {
        val out = mutableListOf<String>()
        while (iterator.hasNext()) {
            val value = iterator.next()
            if (!value.isNullOrBlank()) out += value
        }
        return out
    }

    companion object {
        const val ACTION_START = "com.layer.app.START"
        const val ACTION_STOP = "com.layer.app.STOP"
        const val ACTION_RELOAD = "com.layer.app.RELOAD"
        const val ACTION_REWIRE = "com.layer.app.REWIRE"
        const val EXTRA_SERVER_NAME = "com.layer.app.EXTRA_SERVER_NAME"
        private const val LOG_CLIENT_REATTACH_DELAY_MS = 1_000L
        private const val LOG_CLIENT_REATTACH_MAX = 3

        @Volatile
        private var running: LayerVpnService? = null

        fun wakeAfterHandoff(reason: String) {
            running?.recoverAfterHandoff(reason)
        }

        fun recheckLockdown() {
            running?.recheckTunExclusions("app-resume")
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
