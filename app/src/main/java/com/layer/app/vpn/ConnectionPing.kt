package com.layer.app.vpn

import com.layer.app.data.LayerRepository
import com.layer.app.diagnostics.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ConnectionPingUi(
    val latencyMs: Long? = null,
    val probing: Boolean = false,
)

/**
 * Latency shown on the home screen. Independent from auto-select: tap-to-retest
 * and connect/reconnect measurements never update AutoServerSelector memory.
 */
class ConnectionPing(
    private val repository: LayerRepository,
    private val vpnController: VpnController,
    private val diagnostics: DiagnosticLog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val _status = MutableStateFlow(ConnectionPingUi())
    val status: StateFlow<ConnectionPingUi> = _status.asStateFlow()

    fun clear() {
        _status.value = ConnectionPingUi()
    }

    fun publishFromAutoSelect(latencyMs: Long?) {
        if (_status.value.probing) return
        _status.value = ConnectionPingUi(latencyMs = latencyMs, probing = false)
    }

    fun retest() {
        if (vpnController.status.value.state != VpnConnectionState.CONNECTED) return
        if (_status.value.probing) return
        scope.launch { measureCurrent("tap") }
    }

    fun measureAfterConnected(reason: String) {
        scope.launch { measureCurrent(reason) }
    }

    suspend fun measureCurrent(reason: String) {
        mutex.withLock {
            val server = repository.currentSnapshot().settings.activeServer() ?: run {
                diagnostics.append("[PING] event=skip trigger=$reason why=no-active-server")
                _status.value = ConnectionPingUi()
                return@withLock
            }
            _status.value = _status.value.copy(probing = true)
            diagnostics.append("[PING] event=start trigger=$reason server=${server.visibleName()}")
            // Layer is excluded from its own VPN, so the default route already
            // uses the underlying network. Binding to it after TUN is up gets
            // EPERM on Pixel / Android 15+.
            val ms = VlessTcpProbe.measureMedian(server, null, diagnostics, "[PING]")
            _status.value = ConnectionPingUi(latencyMs = ms, probing = false)
        }
    }
}
