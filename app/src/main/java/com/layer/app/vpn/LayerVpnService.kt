package com.layer.app.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.layer.app.data.RuleSetDownloader
import com.layer.core.config.RuleSetCatalog
import com.layer.core.config.AutoServerPolicy
import com.layer.core.config.IdleRecoveryPolicy
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetAddress

class LayerVpnService : VpnService(), CommandServerHandler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val notification by lazy { VpnNotification(this) }
    private var vpnFd: ParcelFileDescriptor? = null
    private var commandServer: CommandServer? = null
    private var logClient: CommandClient? = null
    private var platform: SingBoxPlatform? = null

    private val container get() = (application as LayerApp).container
    private var stopping = false
    private var serverName: String = ""
    private var screenReceiver: BroadcastReceiver? = null
    private var startedElapsed = 0L
    private var lastIdleRecoverElapsed = 0L
    private var lastScreenOffElapsed = 0L

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
            else -> {
                // START, Always-on VPN, or reboot: system may pass a null action.
                stopping = false
                val state = VpnStatusStore.status.value.state
                if (commandServer != null &&
                    (state == VpnConnectionState.CONNECTED ||
                        state == VpnConnectionState.CONNECTING ||
                        state == VpnConnectionState.RECONNECTING)
                ) {
                    dbg("[VPN] start пропуск, уже $state")
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

    override fun onDestroy() {
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
        if (commandServer != null &&
            (already == VpnConnectionState.CONNECTED ||
                already == VpnConnectionState.CONNECTING ||
                already == VpnConnectionState.RECONNECTING)
        ) {
            dbg("[VPN] startVpn пропуск, уже $already")
            return
        }
        var settings = container.repository.currentSnapshot().settings
        if (stopping) return
        val autoPick = settings.autoSelectServerEnabled &&
            AutoServerPolicy.canEnable(settings.servers.size)
        dbg(
            "[AUTO] startVpn auto=${settings.autoSelectServerEnabled} " +
                "servers=${settings.servers.size} pick=$autoPick",
        )
        updateStatus(
            VpnConnectionState.CONNECTING,
            if (autoPick) getString(R.string.status_checking_servers) else getString(R.string.status_connecting_ellipsis),
        )
        if (autoPick) {
            container.autoServerSelector.prepareBestServer()
            settings = container.repository.currentSnapshot().settings
            dbg("[AUTO] startVpn после выбора ${settings.activeConnectionName()}")
        }
        serverName = settings.activeConnectionName()
        val host = settings.server.address
        dbg(
            "[VPN] start server=${settings.activeConnectionName()} " +
                "host=$host:${settings.server.port} sni=${settings.server.serverName} " +
                "security=${settings.server.security} fp=${settings.server.fingerprint} " +
                "flow=${settings.server.flow} network=${settings.server.network} " +
                    "reality=${settings.server.isReality}",
        )
        val resolved = UnderlyingDns.resolve(this, host)
        if (resolved.ip != null) {
            dbg("[DNS] сервер $host → ${resolved.ip}")
        } else {
            dbg(
                "[DNS] не удалось заранее разрешить $host" +
                    (resolved.error?.let { ": $it" } ?: ""),
            )
        }
        if (settings.server.isReality) {
            dbg("[VPN] Reality dest/SNI=${settings.server.serverName} fp=${settings.server.fingerprint} sid=${settings.server.shortId.isNotBlank()}")
        }
        if (stopping) return
        val localRuleSets = prepareLocalRuleSets()
        if (stopping) return
        val generated = container.repository.buildConfig(
            resolved.ip,
            localRuleSets,
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
                .onSuccess { dbg("[CFG] checkConfig ok, json=${generated.json.length} байт") }
                .onFailure { error ->
                    fail(error.message ?: getString(R.string.error_config_singbox))
                    return
                }
            if (stopping) return
            commandServer = ensureCommandServer()
            dbg("[VPN] startOrReloadService…")
            commandServer?.startOrReloadService(generated.json, OverrideOptions())
            if (stopping) return
            startedElapsed = SystemClock.elapsedRealtime()
            lastIdleRecoverElapsed = startedElapsed
            lastScreenOffElapsed = 0L
            registerIdleRecovery()
            updateStatus(VpnConnectionState.CONNECTED, getString(R.string.status_connected))
            dbg("[VPN] ядро сообщило, что сервис запущен")
            if (settings.autoSelectServerEnabled && AutoServerPolicy.canEnable(settings.servers.size)) {
                container.autoServerSelector.startMonitoring()
            } else if (settings.autoSelectServerEnabled) {
                dbg("[AUTO] мониторинг не стартовал: серверов ${settings.servers.size}")
            }
            container.connectionPing.measureAfterConnected("connected")
            tryPromoteRuleSetsViaProxy(resolved.ip, localRuleSets)
        } catch (error: Exception) {
            fail(combineErrors(error))
        }
    }

    private suspend fun reloadInternal() = lifecycleMutex.withLock { reloadInternalLocked() }

    private suspend fun reloadInternalLocked() {
        if (stopping) return
        if (commandServer == null) {
            startVpnLocked()
            return
        }
        val settings = container.repository.currentSnapshot().settings
        if (stopping) return
        serverName = settings.activeConnectionName()
        updateStatus(VpnConnectionState.RECONNECTING, getString(R.string.status_reconnecting))
        val host = settings.server.address
        val resolved = UnderlyingDns.resolve(this, host)
        val localRuleSets = prepareLocalRuleSets()
        val wantRemoteLists = settings.automaticRuleSetEnabled &&
            localRuleSets.size < RuleSetCatalog.vpnLists.size
        val generated = container.repository.buildConfig(
            resolved.ip,
            localRuleSets,
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
            if (stopping) return
            startedElapsed = SystemClock.elapsedRealtime()
            lastIdleRecoverElapsed = startedElapsed
            lastScreenOffElapsed = 0L
            registerIdleRecovery()
            updateStatus(VpnConnectionState.CONNECTED, getString(R.string.status_connected))
            dbg("[VPN] reload startOrReloadService ok")
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
        localRuleSets: Map<String, String>,
    ) {
        if (stopping) return
        val enabled = container.repository.currentSnapshot().settings.automaticRuleSetEnabled
        if (stopping || !enabled || localRuleSets.size >= RuleSetCatalog.vpnLists.size) return
        val withRemote = container.repository.buildConfig(
            resolvedIp,
            localRuleSets,
            remoteRuleSetFallback = true,
        )
        if (!withRemote.isSuccess) return
        if (runCatching { Libbox.checkConfig(withRemote.json) }.isFailure) return

        val promoted = runCatching {
            commandServer?.startOrReloadService(withRemote.json, OverrideOptions())
        }
        if (promoted.isSuccess) {
            dbg("[RULE] remote rule-sets подняты через proxy")
            return
        }
        dbg("[RULE] remote через proxy не вышли: ${promoted.exceptionOrNull()?.message}")

        val withoutRemote = container.repository.buildConfig(
            resolvedIp,
            localRuleSets,
            remoteRuleSetFallback = false,
        )
        val reverted = runCatching {
            check(withoutRemote.isSuccess) { withoutRemote.error ?: getString(R.string.error_config) }
            Libbox.checkConfig(withoutRemote.json)
            commandServer?.startOrReloadService(withoutRemote.json, OverrideOptions())
        }
        if (reverted.isFailure) {
            dbg(
                "[RULE] откат после списков не вышел: ${reverted.exceptionOrNull()?.message}. " +
                    "VPN уже был поднят — оставляем как есть.",
            )
            return
        }
        dbg("[RULE] списки через VPN не загрузились, работаем без автосписков")
    }

    private fun ensureCommandServer(): CommandServer {
        commandServer?.let { return it }
        val iface = platform ?: SingBoxPlatform(this).also { platform = it }
        dbg("[BOX] CommandServer start, libbox=${Branding.libboxVersion(runCatching { Libbox.version() }.getOrNull())}")
        val server = CommandServer(this, iface)
        server.start()
        commandServer = server
        attachLogClient()
        return server
    }

    private fun attachLogClient() {
        runCatching { logClient?.disconnect() }
        logClient = null
        val options = CommandClientOptions().apply {
            addCommand(Libbox.CommandLog)
            addCommand(Libbox.CommandStatus)
            statusInterval = 1_000_000_000L
        }
        val client = CommandClient(SingBoxLogBridge(container.diagnostics), options)
        val connected = runCatching { client.connect() }
        if (connected.isFailure) {
            dbg("[BOX] log client connect failed: ${connected.exceptionOrNull()?.message}")
            runCatching { client.connect() }
                .onFailure { dbg("[BOX] log client retry failed: ${it.message}") }
                .onSuccess { dbg("[BOX] log client retry ok") }
        } else {
            dbg("[BOX] log client connect ok")
        }
        logClient = client
    }

    private fun stopVpn() {
        if (stopping) return
        stopping = true
        dbg("[VPN] stop")
        container.autoServerSelector.stopMonitoring()
        container.connectionPing.clear()
        unregisterIdleRecovery()
        VpnStatusStore.clearTraffic()
        VpnStatusStore.update(VpnUiStatus(VpnConnectionState.DISCONNECTED, getString(R.string.status_disconnected_short)))
        runCatching { logClient?.disconnect() }
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        runCatching { vpnFd?.close() }
        logClient = null
        commandServer = null
        vpnFd = null
        platform = null
        startedElapsed = 0L
        lastIdleRecoverElapsed = 0L
        lastScreenOffElapsed = 0L
        notification.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun prepareLocalRuleSets(): Map<String, String> {
        val enabled = container.repository.currentSnapshot().settings.automaticRuleSetEnabled
        if (!enabled) return emptyMap()
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val network = UnderlyingDns.pickUnderlyingNetwork(connectivity)
        val downloader = RuleSetDownloader(this)
        val fetched = downloader.ensureDirectCopies(network)
        val local = fetched.files
        val total = RuleSetCatalog.vpnLists.size
        val missing = total - local.size
        dbg(
            when {
                fetched.seededFromAssets > 0 && local.size == total ->
                    "[RULE] списки из APK (${RuleSetCatalog.BUNDLED_RELEASE}), GitHub недоступен"
                local.size == total -> "[RULE] списки доменов скачаны напрямую (${local.size})"
                local.isEmpty() ->
                    "[RULE] списки доменов напрямую недоступны" +
                        (fetched.error?.let { ": $it" } ?: "")
                fetched.seededFromAssets > 0 ->
                    "[RULE] списки: ${local.size} (из них ${fetched.seededFromAssets} из APK), $missing нет" +
                        (fetched.error?.let { " ($it)" } ?: "")
                else ->
                    "[RULE] списки: ${local.size} напрямую, $missing нет" +
                        (fetched.error?.let { " ($it)" } ?: "")
            },
        )
        return local
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
        container.diagnostics.append(mapped.title)
        if (mapped.details.isNotBlank()) {
            container.diagnostics.append(mapped.details)
        }
        if (sanitizedRaw.isNotBlank() && sanitizedRaw != mapped.title && sanitizedRaw != mapped.details) {
            container.diagnostics.append(sanitizedRaw)
        }
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
        runCatching { logClient?.disconnect() }
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        runCatching { vpnFd?.close() }
        logClient = null
        commandServer = null
        vpnFd = null
        platform = null
        startedElapsed = 0L
        lastIdleRecoverElapsed = 0L
        lastScreenOffElapsed = 0L
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
        if (stopping || commandServer == null) return
        val now = SystemClock.elapsedRealtime()
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val hasNetwork = UnderlyingDns.pickUnderlyingNetwork(connectivity) != null
        val action = IdleRecoveryPolicy.decide(
            nowElapsed = now,
            startedElapsed = startedElapsed,
            lastRecoverElapsed = lastIdleRecoverElapsed,
            screenOffElapsed = lastScreenOffElapsed,
            longIdleReload = longIdleReload,
            hasNetwork = hasNetwork,
        )
        if (action == IdleRecoveryPolicy.Action.Skip) {
            if (!hasNetwork) {
                dbg("[VPN] recover skip no-network ($reason)")
            }
            return
        }
        lastIdleRecoverElapsed = now
        if (longIdleReload) {
            lastScreenOffElapsed = 0L
        }
        dbg("[VPN] recover wake ($reason)")
        runCatching { commandServer?.wake() }
    }

    private fun registerIdleRecovery() {
        if (screenReceiver != null) return
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        lastScreenOffElapsed = SystemClock.elapsedRealtime()
                    }
                    Intent.ACTION_SCREEN_ON,
                    Intent.ACTION_USER_PRESENT,
                    -> {
                        recoverAfterIdle(intent.action ?: "screen", longIdleReload = true)
                        container.autoServerSelector.onDeviceBecameInteractive()
                    }
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                        val idle = getSystemService(PowerManager::class.java).isDeviceIdleMode
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
    }

    private fun unregisterIdleRecovery() {
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
        if (prepare(this) != null) error("android: missing vpn permission")
        val dump = StringBuilder()
        fun note(line: String) {
            dump.appendLine(line)
            dbg(line)
        }
        val builder = Builder()
            .setSession("Layer")
            .setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
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
                note("[TUN] libbox не дал DNS — ставлю 1.1.1.1 и 8.8.8.8")
            }
            dnsServers.forEach { server ->
                builder.addDnsServer(server)
                note("[TUN] dns $server")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val inet4Routes = drainPrefixes(options.inet4RouteAddress)
                if (inet4Routes.isNotEmpty()) {
                    inet4Routes.forEach { (address, prefix) ->
                        builder.addRoute(address, prefix)
                        note("[TUN] route $address/$prefix")
                    }
                } else {
                    builder.addRoute("0.0.0.0", 0)
                    note("[TUN] route 0.0.0.0/0 (fallback, inet4RouteAddress пуст)")
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
            } else {
                drainPrefixes(options.inet4RouteRange).forEach { (address, prefix) ->
                    builder.addRoute(address, prefix)
                    note("[TUN] range $address/$prefix")
                }
                drainPrefixes(options.inet6RouteRange).forEach { (address, prefix) ->
                    builder.addRoute(address, prefix)
                    note("[TUN] range6 $address/$prefix")
                }
            }
            drainStrings(options.includePackage).forEach { pkg ->
                runCatching { builder.addAllowedApplication(pkg) }
                    .onSuccess { note("[TUN] allow $pkg") }
                    .onFailure { note("[TUN] allow $pkg failed: ${it.message}") }
            }
            drainStrings(options.excludePackage).forEach { pkg ->
                runCatching { builder.addDisallowedApplication(pkg) }
                    .onSuccess { note("[TUN] disallow $pkg") }
                    .onFailure { note("[TUN] disallow $pkg failed: ${it.message}") }
            }
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
        val pfd = builder.establish() ?: error("android: the application is not prepared or is revoked")
        vpnFd = pfd
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
        const val EXTRA_SERVER_NAME = "com.layer.app.EXTRA_SERVER_NAME"

        fun start(context: Context, serverName: String = "") {
            val intent = Intent(context, LayerVpnService::class.java).setAction(ACTION_START)
            if (serverName.isNotBlank()) {
                intent.putExtra(EXTRA_SERVER_NAME, serverName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(stopIntent(context))
        }

        fun reload(context: Context) {
            val intent = Intent(context, LayerVpnService::class.java).setAction(ACTION_RELOAD)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, LayerVpnService::class.java).setAction(ACTION_STOP)
        }
    }
}
