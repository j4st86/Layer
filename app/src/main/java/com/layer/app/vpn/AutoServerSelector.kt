package com.layer.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.os.SystemClock
import com.layer.app.data.LayerRepository
import com.layer.app.diagnostics.DiagnosticLog
import com.layer.core.config.AutoServerPolicy
import com.layer.core.model.LayerSettings
import com.layer.core.model.SavedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AutoServerUi(
    val probing: Boolean = false,
    val latencyMs: Long? = null,
)

private data class ProbeMemory(
    val latencyMs: Long?,
    val reachable: Boolean,
    val checkedAtElapsed: Long,
    val consecutiveFailures: Int,
)

class AutoServerSelector(
    private val context: Context,
    private val repository: LayerRepository,
    private val vpnController: VpnController,
    private val diagnostics: DiagnosticLog,
    private val connectionPing: ConnectionPing,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val memory = mutableMapOf<String, ProbeMemory>()
    private val _status = MutableStateFlow(AutoServerUi())
    val status: StateFlow<AutoServerUi> = _status.asStateFlow()

    private var monitorJob: Job? = null
    private var lastSwitchElapsed = 0L
    private var lastGoodCurrentMs: Long? = null
    private var lastTransportKind = -1
    private var networkJob: Job? = null
    private var handoffRetryJob: Job? = null
    private var wakeJob: Job? = null
    private var lastEvaluateElapsed = 0L
    private var lastNetworkChangeElapsed = 0L

    init {
        scope.launch {
            repository.settings
                .map { it.autoSelectServerEnabled to it.servers.size }
                .distinctUntilChanged()
                .collect { (enabled, count) ->
                    log("event=settings enabled=$enabled servers=$count")
                    if (enabled && !AutoServerPolicy.canEnable(count)) {
                        disableAuto("too-few-servers count=$count")
                    }
                }
        }
    }

    suspend fun prepareBestServer(): Boolean {
        val settings = repository.currentSnapshot().settings
        if (!settings.autoSelectServerEnabled) {
            log("event=prepare action=skip reason=disabled")
            return false
        }
        if (!AutoServerPolicy.canEnable(settings.servers.size)) {
            log(
                "event=prepare action=skip reason=too-few-servers " +
                    "count=${settings.servers.size} need=${AutoServerPolicy.minServers}",
            )
            return false
        }
        return mutex.withLock {
            _status.value = _status.value.copy(probing = true)
            try {
                log(
                    "event=prepare action=start servers=${settings.servers.size} " +
                        "current=${nameOf(settings, settings.activeServerId)} " +
                        "transport=${transportLabel(currentTransportKind())}",
                )
                val best = probeAll(settings.servers)
                log("event=prepare action=results latencies=${formatLatencies(settings, best.mapValues { it.value })}")
                val winner = best.minByOrNull { it.value }
                if (winner == null) {
                    log(
                        "event=prepare action=keep reason=all-miss " +
                            "current=${nameOf(settings, settings.activeServerId)}",
                    )
                    _status.value = AutoServerUi(probing = false, latencyMs = null)
                    false
                } else {
                    lastGoodCurrentMs = winner.value
                    _status.value = AutoServerUi(probing = false, latencyMs = winner.value)
                    connectionPing.publishFromAutoSelect(winner.value)
                    if (winner.key == settings.activeServerId) {
                        log(
                            "event=prepare action=keep reason=already-best " +
                                "server=${nameOf(settings, winner.key)} latencyMs=${winner.value}",
                        )
                        false
                    } else {
                        log(
                            "event=prepare action=pick " +
                                "server=${nameOf(settings, winner.key)} latencyMs=${winner.value}",
                        )
                        repository.selectServer(winner.key)
                        true
                    }
                }
            } finally {
                if (_status.value.probing) {
                    _status.value = _status.value.copy(probing = false)
                }
            }
        }
    }

    fun startMonitoring() {
        if (lastTransportKind == -1) {
            lastTransportKind = currentTransportKind()
        }
        val restart = monitorJob?.isActive == true
        monitorJob?.cancel()
        monitorJob = scope.launch {
            val settings = repository.currentSnapshot().settings
            val minutes = AutoServerPolicy.clampInterval(settings.autoSelectIntervalMinutes)
            log(
                "event=monitor action=${if (restart) "restart" else "start"} " +
                    "intervalMin=$minutes transport=${transportLabel(lastTransportKind)} " +
                    "current=${nameOf(settings, settings.activeServerId)} " +
                    "servers=${settings.servers.size}",
            )
            while (true) {
                val snap = repository.currentSnapshot().settings
                if (!snap.autoSelectServerEnabled) {
                    log("event=monitor action=stop reason=disabled")
                    return@launch
                }
                val waitMin = AutoServerPolicy.clampInterval(snap.autoSelectIntervalMinutes)
                val interactive = isInteractive()
                val waitMs = AutoServerPolicy.monitorDelayMs(waitMin, interactive)
                if (!interactive) {
                    log("event=monitor action=wait-idle delayMin=${waitMs / 60_000L}")
                }
                delay(waitMs)
                if (AutoServerPolicy.shouldSkipPeriodicProbe(isInteractive())) {
                    log("event=monitor action=skip reason=screen-off-or-doze")
                    continue
                }
                log(
                    "event=monitor action=interval intervalMin=$waitMin " +
                        "current=${nameOf(snap, snap.activeServerId)}",
                )
                evaluateConnected(fullScan = false)
            }
        }
    }

    fun stopMonitoring() {
        if (monitorJob != null || networkJob != null || wakeJob != null) {
            log("event=monitor action=stop")
        }
        monitorJob?.cancel()
        monitorJob = null
        networkJob?.cancel()
        networkJob = null
        handoffRetryJob?.cancel()
        handoffRetryJob = null
        wakeJob?.cancel()
        wakeJob = null
    }

    fun onNetworkTransportChanged(kind: Int) {
        if (kind == lastTransportKind || lastTransportKind == -1) {
            if (lastTransportKind == -1) {
                log("event=transport action=start transport=${transportLabel(kind)}")
            }
            lastTransportKind = kind
            return
        }
        val from = lastTransportKind
        lastTransportKind = kind
        lastNetworkChangeElapsed = SystemClock.elapsedRealtime()
        memory.replaceAll { _, value -> value.copy(consecutiveFailures = 0) }
        log(
            "event=transport from=${transportLabel(from)} to=${transportLabel(kind)} " +
                "debounceMs=${AutoServerPolicy.networkChangeDebounceMs}",
        )
        networkJob?.cancel()
        handoffRetryJob?.cancel()
        networkJob = scope.launch {
            delay(AutoServerPolicy.networkChangeDebounceMs)
            val connected = vpnController.status.value.state == VpnConnectionState.CONNECTED
            if (!connected) {
                log("event=handoff action=skip reason=vpn-state state=${vpnController.status.value.state}")
                return@launch
            }
            vpnController.wakeAfterHandoff("wifi-cell")
            val snap = repository.currentSnapshot().settings
            if (!snap.autoSelectServerEnabled) {
                log("event=handoff action=wake auto=false")
                return@launch
            }
            log("event=handoff action=evaluate")
            evaluateConnected(fullScan = false)
        }
    }

    fun onDeviceBecameInteractive() {
        wakeJob?.cancel()
        wakeJob = scope.launch {
            delay(400)
            val snap = repository.currentSnapshot().settings
            if (!snap.autoSelectServerEnabled) return@launch
            val connected = vpnController.status.value.state == VpnConnectionState.CONNECTED
            if (!connected) return@launch
            val intervalMs = AutoServerPolicy.monitorDelayMs(
                snap.autoSelectIntervalMinutes,
                interactive = true,
            )
            val ago = if (lastEvaluateElapsed == 0L) {
                Long.MAX_VALUE
            } else {
                SystemClock.elapsedRealtime() - lastEvaluateElapsed
            }
            val suspect = currentIsSuspect(snap)
            if (ago < intervalMs && !suspect) {
                log("event=screen-on action=skip reason=recent-probe agoSec=${ago / 1000}")
                return@launch
            }
            log("event=screen-on action=evaluate agoSec=${if (ago == Long.MAX_VALUE) "never" else ago / 1000}")
            evaluateConnected(fullScan = false)
        }
    }

    fun notifyEnabledWhileConnected() {
        scope.launch {
            log("event=ui-enable-while-connected vpn=${vpnController.status.value.state}")
            val changed = prepareBestServer()
            val connected = vpnController.status.value.state == VpnConnectionState.CONNECTED ||
                vpnController.status.value.state == VpnConnectionState.RECONNECTING
            if (connected) {
                startMonitoring()
                if (changed) {
                    log("event=reload-after-pick changed=true")
                    vpnController.reload()
                } else {
                    log("event=reload-after-pick changed=false")
                }
            } else {
                log("event=monitor action=skip reason=vpn-state state=${vpnController.status.value.state}")
            }
        }
    }

    private suspend fun evaluateConnected(fullScan: Boolean) {
        val settings = repository.currentSnapshot().settings
        if (!settings.autoSelectServerEnabled) {
            log("event=evaluate action=skip reason=disabled")
            return
        }
        if (!AutoServerPolicy.canEnable(settings.servers.size)) {
            log("event=evaluate action=skip reason=too-few-servers count=${settings.servers.size}")
            return
        }
        val connected = vpnController.status.value.state == VpnConnectionState.CONNECTED
        if (!connected) {
            log("event=evaluate action=skip reason=vpn-state state=${vpnController.status.value.state}")
            return
        }
        if (underlyingNetwork() == null) {
            log("event=evaluate action=skip reason=no-network")
            return
        }
        lastEvaluateElapsed = SystemClock.elapsedRealtime()
        mutex.withLock {
            val currentId = settings.activeServerId ?: run {
                log("event=evaluate action=skip reason=no-active-id")
                return@withLock
            }
            val current = settings.servers.find { it.id == currentId } ?: run {
                log("event=evaluate action=skip reason=server-missing")
                return@withLock
            }
            val network = underlyingNetwork()
            val settling = AutoServerPolicy.isNetworkSettling(
                SystemClock.elapsedRealtime(),
                lastNetworkChangeElapsed,
            )
            log(
                "event=evaluate action=start current=${current.visibleName()} fullScan=$fullScan " +
                    "ewma=${fmtMs(lastGoodCurrentMs)} cooldownMs=${cooldownLeftMs()} " +
                    "settling=$settling " +
                    "bind=${if (network != null) "underlying" else "default"}",
            )
            val previousGood = lastGoodCurrentMs
            var currentMs = VlessTcpProbe.measureMedian(current, network, diagnostics)
            val firstDegraded = currentMs != null &&
                previousGood != null &&
                AutoServerPolicy.isDegraded(currentMs, previousGood)
            if (currentMs == null) {
                log("event=current action=retry reason=miss server=${current.visibleName()}")
                currentMs = VlessTcpProbe.measureMedian(current, network, diagnostics)
            } else if (firstDegraded) {
                log(
                    "event=current action=retry reason=degraded " +
                        "server=${current.visibleName()} latencyMs=$currentMs ewmaMs=$previousGood",
                )
                currentMs = VlessTcpProbe.measureMedian(current, network, diagnostics)
            }
            remember(
                currentId,
                currentMs,
                countFailure = AutoServerPolicy.countMissTowardFailover(settling),
            )
            if (currentMs != null) {
                lastGoodCurrentMs = AutoServerPolicy.ewma(previousGood, currentMs)
                val degraded = previousGood != null &&
                    AutoServerPolicy.isDegraded(currentMs, previousGood)
                _status.value = _status.value.copy(latencyMs = currentMs)
                connectionPing.publishFromAutoSelect(currentMs)
                if (!fullScan && !degraded) {
                    log(
                        "event=current action=stable server=${current.visibleName()} " +
                            "latencyMs=$currentMs skipOthers=true",
                    )
                    return@withLock
                }
                if (degraded) {
                    log(
                        "event=current action=degraded server=${current.visibleName()} " +
                            "latencyMs=$currentMs scanOthers=true",
                    )
                }
            } else {
                _status.value = _status.value.copy(latencyMs = null)
                connectionPing.publishFromAutoSelect(null)
                val failures = memory[currentId]?.consecutiveFailures ?: 1
                if (settling || !AutoServerPolicy.shouldFailover(failures)) {
                    log(
                        "event=current action=miss server=${current.visibleName()} " +
                            "failures=$failures/${AutoServerPolicy.failuresBeforeFullScan} " +
                            "settling=$settling failover=false " +
                            "hold=${if (settling) "wait-after-handoff" else "need-more-failures"}",
                    )
                    if (settling) scheduleHandoffRetry()
                    return@withLock
                }
                log("event=current action=dead server=${current.visibleName()} scanOthers=true")
            }
            val others = settings.servers.filter { it.id != currentId }
            val all = VlessTcpProbe.measureAll(others, network, diagnostics)
            all.forEach { (id, ms) -> remember(id, ms) }
            log("event=others latencies=${formatLatencies(settings, all)}")
            val reachable = buildMap {
                if (currentMs != null) put(currentId, currentMs)
                all.forEach { (id, ms) -> if (ms != null) put(id, ms) }
            }
            if (currentMs == null && reachable.isNotEmpty()) {
                val winner = reachable.minBy { it.value }
                log(
                    "event=switch action=force reason=current-dead " +
                        "to=${nameOf(settings, winner.key)} latencyMs=${winner.value}",
                )
                switchTo(settings, winner.key, winner.value, force = true)
                return@withLock
            }
            if (currentMs == null && reachable.isEmpty()) {
                log("event=switch action=skip reason=all-miss keep=${current.visibleName()}")
                return@withLock
            }
            val candidate = reachable.filterKeys { it != currentId }.minByOrNull { it.value } ?: run {
                log("event=switch action=skip reason=no-other-reachable")
                return@withLock
            }
            val baseline = currentMs ?: return@withLock
            if (!AutoServerPolicy.shouldSwitch(baseline, candidate.value)) {
                val need = (baseline * (1.0 - AutoServerPolicy.switchImprovementRatio)).toLong()
                log(
                    "event=switch action=skip reason=not-enough-gain " +
                        "candidate=${nameOf(settings, candidate.key)} candidateMs=${candidate.value} " +
                        "currentMs=$baseline needMs=$need minDeltaMs=${AutoServerPolicy.minSwitchDeltaMs}",
                )
                return@withLock
            }
            if (!forceCooldownElapsed()) {
                log(
                    "event=switch action=skip reason=cooldown " +
                        "candidate=${nameOf(settings, candidate.key)} remainingMs=${cooldownLeftMs()}",
                )
                return@withLock
            }
            if (VpnStatusStore.hasRecentTraffic()) {
                log(
                    "event=switch action=skip reason=recent-tun-traffic " +
                        "candidate=${nameOf(settings, candidate.key)} candidateMs=${candidate.value}",
                )
                return@withLock
            }
            log(
                "event=switch action=confirm candidate=${nameOf(settings, candidate.key)} " +
                    "candidateMs=${candidate.value} current=${current.visibleName()} currentMs=$baseline",
            )
            val confirmed = confirmSoftSwitch(current, candidate.key, settings, network)
            if (confirmed == null) return@withLock
            switchTo(settings, confirmed.first, confirmed.second, force = false)
        }
    }

    private suspend fun probeAll(servers: List<SavedServer>): Map<String, Long> {
        val measured = VlessTcpProbe.measureAll(servers, underlyingNetwork(), diagnostics)
        measured.forEach { (id, ms) -> remember(id, ms) }
        return measured.mapNotNull { (id, ms) -> ms?.let { id to it } }.toMap()
    }

    private suspend fun confirmSoftSwitch(
        current: SavedServer,
        candidateId: String,
        settings: LayerSettings,
        network: Network?,
    ): Pair<String, Long>? {
        val candidate = settings.servers.find { it.id == candidateId } ?: return null
        val currentMs = VlessTcpProbe.measureMedian(current, network, diagnostics)
        remember(current.id, currentMs)
        val candidateMs = VlessTcpProbe.measureMedian(candidate, network, diagnostics)
        remember(candidate.id, candidateMs)
        if (currentMs == null) {
            log("event=confirm action=abort reason=current-miss")
            return null
        }
        lastGoodCurrentMs = AutoServerPolicy.ewma(lastGoodCurrentMs, currentMs)
        _status.value = _status.value.copy(latencyMs = currentMs)
        connectionPing.publishFromAutoSelect(currentMs)
        if (candidateMs == null) {
            log("event=confirm action=abort reason=candidate-miss server=${candidate.visibleName()}")
            return null
        }
        if (!AutoServerPolicy.shouldSwitch(currentMs, candidateMs)) {
            log(
                "event=confirm action=abort reason=gap-gone " +
                    "current=${current.visibleName()} currentMs=$currentMs " +
                    "candidate=${candidate.visibleName()} candidateMs=$candidateMs",
            )
            return null
        }
        if (VpnStatusStore.hasRecentTraffic()) {
            log("event=confirm action=abort reason=recent-tun-traffic")
            return null
        }
        return candidate.id to candidateMs
    }

    private fun currentIsSuspect(settings: LayerSettings): Boolean {
        val id = settings.activeServerId ?: return false
        return (memory[id]?.consecutiveFailures ?: 0) > 0
    }

    private suspend fun switchTo(
        settings: LayerSettings,
        id: String,
        latencyMs: Long,
        force: Boolean,
    ) {
        if (!force && !forceCooldownElapsed()) {
            log(
                "event=switch action=skip reason=cooldown remainingMs=${cooldownLeftMs()} " +
                    "to=${nameOf(settings, id)}",
            )
            return
        }
        val from = nameOf(settings, settings.activeServerId)
        lastSwitchElapsed = SystemClock.elapsedRealtime()
        lastGoodCurrentMs = latencyMs
        _status.value = _status.value.copy(latencyMs = latencyMs)
        connectionPing.publishFromAutoSelect(latencyMs)
        log(
            "event=switch action=${if (force) "force" else "soft"} from=$from " +
                "to=${nameOf(settings, id)} latencyMs=$latencyMs",
        )
        repository.selectServer(id)
        vpnController.reload()
    }

    private fun forceCooldownElapsed(): Boolean {
        if (lastSwitchElapsed == 0L) return true
        return SystemClock.elapsedRealtime() - lastSwitchElapsed >= AutoServerPolicy.cooldownMs
    }

    private fun cooldownLeftMs(): Long {
        if (lastSwitchElapsed == 0L) return 0L
        val left = AutoServerPolicy.cooldownMs - (SystemClock.elapsedRealtime() - lastSwitchElapsed)
        return left.coerceAtLeast(0L)
    }

    private fun remember(id: String, latencyMs: Long?, countFailure: Boolean = true) {
        val previous = memory[id]
        val failures = when {
            latencyMs != null -> 0
            !countFailure -> previous?.consecutiveFailures ?: 0
            else -> (previous?.consecutiveFailures ?: 0) + 1
        }
        memory[id] = ProbeMemory(
            latencyMs = latencyMs,
            reachable = latencyMs != null,
            checkedAtElapsed = SystemClock.elapsedRealtime(),
            consecutiveFailures = failures,
        )
    }

    private fun scheduleHandoffRetry() {
        if (handoffRetryJob?.isActive == true) return
        val wait = AutoServerPolicy.remainingSettlingMs(
            SystemClock.elapsedRealtime(),
            lastNetworkChangeElapsed,
        ).coerceAtLeast(3_000L)
        log("event=handoff-retry action=schedule waitMs=$wait")
        handoffRetryJob = scope.launch {
            delay(wait)
            if (vpnController.status.value.state != VpnConnectionState.CONNECTED) return@launch
            log("event=handoff-retry action=run")
            vpnController.wakeAfterHandoff("wifi-cell-retry")
            delay(400)
            evaluateConnected(fullScan = false)
        }
    }

    private suspend fun disableAuto(reason: String) {
        val current = repository.currentSnapshot().settings
        if (!current.autoSelectServerEnabled) return
        log("event=disable reason=$reason")
        repository.saveSettings(current.copy(autoSelectServerEnabled = false))
        stopMonitoring()
        _status.value = AutoServerUi()
    }

    private fun isInteractive(): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return true
        return pm.isInteractive && !pm.isDeviceIdleMode
    }

    private fun underlyingNetwork(): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return UnderlyingDns.pickUnderlyingNetwork(cm)
    }

    private fun currentTransportKind(): Int {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = UnderlyingDns.pickUnderlyingNetwork(cm) ?: return -1
        val caps = cm.getNetworkCapabilities(network) ?: return -1
        return transportKind(caps)
    }

    private fun nameOf(settings: LayerSettings, id: String?): String =
        id?.let { settings.servers.find { server -> server.id == it }?.visibleName() } ?: "—"

    private fun formatLatencies(settings: LayerSettings, values: Map<String, Long?>): String {
        if (values.isEmpty()) return "—"
        return settings.servers
            .filter { it.id in values }
            .joinToString { "${it.visibleName()}=${fmtMs(values[it.id])}" }
    }

    private fun log(message: String) {
        diagnostics.append("[AUTO] $message")
    }

    companion object {
        fun transportKind(caps: NetworkCapabilities): Int = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
            else -> 0
        }

        fun transportLabel(kind: Int): String = when (kind) {
            1 -> "wifi"
            2 -> "cell"
            0 -> "other"
            else -> "unknown"
        }

        fun fmtMs(ms: Long?): String = ms?.let { "$it" } ?: "miss"
    }
}
