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
    private var wakeJob: Job? = null
    private var lastEvaluateElapsed = 0L

    init {
        scope.launch {
            repository.settings
                .map { it.autoSelectServerEnabled to it.servers.size }
                .distinctUntilChanged()
                .collect { (enabled, count) ->
                    log("настройки enabled=$enabled servers=$count")
                    if (enabled && !AutoServerPolicy.canEnable(count)) {
                        disableAuto("мало серверов ($count)")
                    }
                }
        }
    }

    suspend fun prepareBestServer(): Boolean {
        val settings = repository.currentSnapshot().settings
        if (!settings.autoSelectServerEnabled) {
            log("prepare пропуск: автовыбор выключен")
            return false
        }
        if (!AutoServerPolicy.canEnable(settings.servers.size)) {
            log("prepare пропуск: серверов ${settings.servers.size}, нужно ${AutoServerPolicy.minServers}")
            return false
        }
        return mutex.withLock {
            _status.value = _status.value.copy(probing = true)
            try {
                log(
                    "prepare старт: ${settings.servers.size} серверов, " +
                        "текущий=${nameOf(settings, settings.activeServerId)} " +
                        "сеть=${transportLabel(currentTransportKind())}",
                )
                val best = probeAll(settings.servers)
                log("prepare результаты: ${formatLatencies(settings, best.mapValues { it.value })}")
                val winner = best.minByOrNull { it.value }
                if (winner == null) {
                    log("prepare: ни один сервер не ответил, оставляем ${nameOf(settings, settings.activeServerId)}")
                    _status.value = AutoServerUi(probing = false, latencyMs = null)
                    false
                } else {
                    lastGoodCurrentMs = winner.value
                    _status.value = AutoServerUi(probing = false, latencyMs = winner.value)
                    connectionPing.publishFromAutoSelect(winner.value)
                    if (winner.key == settings.activeServerId) {
                        log("prepare: лучший уже выбран ${nameOf(settings, winner.key)} · ${winner.value} ms")
                        false
                    } else {
                        log("prepare: выбран ${nameOf(settings, winner.key)} · ${winner.value} ms")
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
                "${if (restart) "мониторинг перезапуск" else "мониторинг старт"}: " +
                    "каждые $minutes мин, сеть=${transportLabel(lastTransportKind)}, " +
                    "текущий=${nameOf(settings, settings.activeServerId)}, " +
                    "серверов=${settings.servers.size}",
            )
            while (true) {
                val snap = repository.currentSnapshot().settings
                if (!snap.autoSelectServerEnabled) {
                    log("мониторинг стоп: автовыбор выключен")
                    return@launch
                }
                val waitMin = AutoServerPolicy.clampInterval(snap.autoSelectIntervalMinutes)
                val interactive = isInteractive()
                val waitMs = AutoServerPolicy.monitorDelayMs(waitMin, interactive)
                if (!interactive) {
                    log("ожидание ${waitMs / 60_000L} мин: устройство неактивно")
                }
                delay(waitMs)
                if (AutoServerPolicy.shouldSkipPeriodicProbe(isInteractive())) {
                    log("интервал пропуск: экран выключен или Doze")
                    continue
                }
                log("интервал ${waitMin} мин: проверка текущего ${nameOf(snap, snap.activeServerId)}")
                evaluateConnected(fullScan = false)
            }
        }
    }

    fun stopMonitoring() {
        if (monitorJob != null || networkJob != null || wakeJob != null) {
            log("мониторинг стоп")
        }
        monitorJob?.cancel()
        monitorJob = null
        networkJob?.cancel()
        networkJob = null
        wakeJob?.cancel()
        wakeJob = null
    }

    fun onNetworkTransportChanged(kind: Int) {
        if (kind == lastTransportKind || lastTransportKind == -1) {
            if (lastTransportKind == -1) {
                log("сеть старт: ${transportLabel(kind)}")
            }
            lastTransportKind = kind
            return
        }
        val from = lastTransportKind
        lastTransportKind = kind
        log("сеть ${transportLabel(from)} → ${transportLabel(kind)}, debounce ${AutoServerPolicy.networkChangeDebounceMs} ms")
        networkJob?.cancel()
        networkJob = scope.launch {
            delay(AutoServerPolicy.networkChangeDebounceMs)
            val snap = repository.currentSnapshot().settings
            if (!snap.autoSelectServerEnabled) {
                log("смена сети пропуск: автовыбор выключен")
                return@launch
            }
            val connected = vpnController.status.value.state == VpnConnectionState.CONNECTED
            if (!connected) {
                log("смена сети пропуск: VPN ${vpnController.status.value.state}")
                return@launch
            }
            log("смена сети: полная оценка")
            evaluateConnected(fullScan = true)
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
            if (ago < intervalMs) {
                log("экран включён, недавняя проверка ${ago / 1000} с назад")
                return@launch
            }
            log("экран включён после простоя, проверка текущего")
            evaluateConnected(fullScan = false)
        }
    }

    fun notifyEnabledWhileConnected() {
        scope.launch {
            log("включён при активном VPN, state=${vpnController.status.value.state}")
            val changed = prepareBestServer()
            val connected = vpnController.status.value.state == VpnConnectionState.CONNECTED ||
                vpnController.status.value.state == VpnConnectionState.RECONNECTING
            if (connected) {
                startMonitoring()
                if (changed) {
                    log("reload после выбора сервера")
                    vpnController.reload()
                } else {
                    log("reload не нужен, сервер не изменился")
                }
            } else {
                log("мониторинг не стартовал: VPN ${vpnController.status.value.state}")
            }
        }
    }

    private suspend fun evaluateConnected(fullScan: Boolean) {
        val settings = repository.currentSnapshot().settings
        if (!settings.autoSelectServerEnabled) {
            log("оценка пропуск: автовыбор выключен")
            return
        }
        if (!AutoServerPolicy.canEnable(settings.servers.size)) {
            log("оценка пропуск: серверов ${settings.servers.size}")
            return
        }
        val connected = vpnController.status.value.state == VpnConnectionState.CONNECTED
        if (!connected) {
            log("оценка пропуск: VPN ${vpnController.status.value.state}")
            return
        }
        lastEvaluateElapsed = SystemClock.elapsedRealtime()
        mutex.withLock {
            val currentId = settings.activeServerId ?: run {
                log("оценка пропуск: нет activeServerId")
                return@withLock
            }
            val current = settings.servers.find { it.id == currentId } ?: run {
                log("оценка пропуск: текущий сервер не найден")
                return@withLock
            }
            val network = underlyingNetwork()
            log(
                "оценка current=${current.visibleName()} fullScan=$fullScan " +
                    "lastGood=${fmtMs(lastGoodCurrentMs)} cooldown=${cooldownLeftMs()} ms " +
                    "bind=${if (network != null) "underlying" else "default"}",
            )
            var currentMs = VlessTcpProbe.measureMedian(current, network, diagnostics)
            remember(currentId, currentMs)
            val previousGood = lastGoodCurrentMs
            if (currentMs != null) {
                val degraded = previousGood != null && AutoServerPolicy.isDegraded(currentMs, previousGood)
                if (degraded) {
                    log(
                        "текущий ухудшился ${current.visibleName()} ${currentMs} ms " +
                            "против lastGood ${previousGood} ms, повторная проверка",
                    )
                    currentMs = VlessTcpProbe.measureMedian(current, network, diagnostics)
                    remember(currentId, currentMs)
                }
            }
            if (currentMs != null) {
                val degraded = previousGood != null && AutoServerPolicy.isDegraded(currentMs, previousGood)
                if (!degraded) lastGoodCurrentMs = currentMs
                _status.value = _status.value.copy(latencyMs = currentMs)
                connectionPing.publishFromAutoSelect(currentMs)
                if (!fullScan && !degraded) {
                    log("текущий стабилен ${current.visibleName()} · ${currentMs} ms, остальные не трогаем")
                    return@withLock
                }
                if (degraded) {
                    log("текущий всё ещё хуже ${current.visibleName()} · ${currentMs} ms, сравниваем остальные")
                }
            } else {
                _status.value = _status.value.copy(latencyMs = null)
                connectionPing.publishFromAutoSelect(null)
                log("текущий недоступен ${current.visibleName()}, ищем другой")
            }
            val others = settings.servers.filter { it.id != currentId }
            val all = VlessTcpProbe.measureAll(others, network, diagnostics)
            all.forEach { (id, ms) -> remember(id, ms) }
            log("остальные: ${formatLatencies(settings, all)}")
            val reachable = buildMap {
                if (currentMs != null) put(currentId, currentMs)
                all.forEach { (id, ms) -> if (ms != null) put(id, ms) }
            }
            if (currentMs == null && reachable.isNotEmpty()) {
                val winner = reachable.minBy { it.value }
                log("текущий мёртв, принудительно ${nameOf(settings, winner.key)} · ${winner.value} ms")
                switchTo(settings, winner.key, winner.value, force = true)
                return@withLock
            }
            if (currentMs == null && reachable.isEmpty()) {
                log("нет доступных серверов, оставляем ${current.visibleName()}")
                return@withLock
            }
            val candidate = reachable.filterKeys { it != currentId }.minByOrNull { it.value } ?: run {
                log("нет других доступных серверов")
                return@withLock
            }
            val baseline = currentMs ?: return@withLock
            if (!AutoServerPolicy.shouldSwitch(baseline, candidate.value)) {
                val need = (baseline * (1.0 - AutoServerPolicy.switchImprovementRatio)).toLong()
                log(
                    "не переключаем: ${nameOf(settings, candidate.key)} ${candidate.value} ms " +
                        "против текущих ${baseline} ms, нужно < $need ms (20%)",
                )
                return@withLock
            }
            if (!forceCooldownElapsed()) {
                log(
                    "не переключаем на ${nameOf(settings, candidate.key)} ${candidate.value} ms: " +
                        "cooldown ещё ${cooldownLeftMs()} ms",
                )
                return@withLock
            }
            log(
                "кандидат лучше: ${nameOf(settings, candidate.key)} ${candidate.value} ms " +
                    "против ${current.visibleName()} ${baseline} ms",
            )
            switchTo(settings, candidate.key, candidate.value, force = false)
        }
    }

    private suspend fun probeAll(servers: List<SavedServer>): Map<String, Long> {
        val measured = VlessTcpProbe.measureAll(servers, underlyingNetwork(), diagnostics)
        measured.forEach { (id, ms) -> remember(id, ms) }
        return measured.mapNotNull { (id, ms) -> ms?.let { id to it } }.toMap()
    }

    private suspend fun switchTo(
        settings: LayerSettings,
        id: String,
        latencyMs: Long,
        force: Boolean,
    ) {
        if (!force && !forceCooldownElapsed()) {
            log("switchTo пропуск cooldown ${cooldownLeftMs()} ms → ${nameOf(settings, id)}")
            return
        }
        val from = nameOf(settings, settings.activeServerId)
        lastSwitchElapsed = SystemClock.elapsedRealtime()
        lastGoodCurrentMs = latencyMs
        _status.value = _status.value.copy(latencyMs = latencyMs)
        connectionPing.publishFromAutoSelect(latencyMs)
        log(
            "переключение ${if (force) "force" else "soft"} $from → ${nameOf(settings, id)} · $latencyMs ms",
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

    private fun remember(id: String, latencyMs: Long?) {
        val previous = memory[id]
        val failures = if (latencyMs == null) (previous?.consecutiveFailures ?: 0) + 1 else 0
        memory[id] = ProbeMemory(
            latencyMs = latencyMs,
            reachable = latencyMs != null,
            checkedAtElapsed = SystemClock.elapsedRealtime(),
            consecutiveFailures = failures,
        )
    }

    private suspend fun disableAuto(reason: String) {
        val current = repository.currentSnapshot().settings
        if (!current.autoSelectServerEnabled) return
        log("выключен: $reason")
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

        fun fmtMs(ms: Long?): String = ms?.let { "$it ms" } ?: "нет ответа"
    }
}
