package com.layer.app.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import com.layer.app.R
import com.layer.core.config.AutoServerPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

object VpnStatusStore {
    private val _status = MutableStateFlow(VpnUiStatus())
    val status: StateFlow<VpnUiStatus> = _status.asStateFlow()

    @Volatile
    var lastTrafficElapsed: Long = 0L
        private set

    fun update(status: VpnUiStatus) {
        _status.value = status
    }

    fun noteTraffic() {
        lastTrafficElapsed = SystemClock.elapsedRealtime()
    }

    fun clearTraffic() {
        lastTrafficElapsed = 0L
    }

    fun hasRecentTraffic(windowMs: Long = AutoServerPolicy.trafficQuietMs): Boolean {
        if (lastTrafficElapsed <= 0L) return false
        return SystemClock.elapsedRealtime() - lastTrafficElapsed <= windowMs
    }
}

class VpnController(
    private val context: Context,
    private val repository: com.layer.app.data.LayerRepository,
    private val diagnostics: com.layer.app.diagnostics.DiagnosticLog,
) {
    val status: StateFlow<VpnUiStatus> = VpnStatusStore.status

    fun prepare(): Intent? = VpnService.prepare(context)

    suspend fun connect(): Result<Unit> {
        val config = repository.buildConfig()
        if (!config.isSuccess) {
            val message = config.error ?: context.getString(R.string.error_config)
            diagnostics.append(message)
            VpnStatusStore.update(
                VpnUiStatus(
                    state = VpnConnectionState.ERROR,
                    message = context.getString(R.string.error_generic),
                    errorTitle = message,
                    errorDetails = message,
                ),
            )
            return Result.failure(IllegalStateException(message))
        }
        val serverName = repository.settings.first().activeConnectionName()
        LayerVpnService.start(context, serverName)
        return Result.success(Unit)
    }

    fun disconnect() {
        LayerVpnService.stop(context)
    }

    fun reload() {
        if (status.value.state == VpnConnectionState.CONNECTED ||
            status.value.state == VpnConnectionState.RECONNECTING
        ) {
            LayerVpnService.reload(context)
        }
    }

    fun wakeAfterHandoff(reason: String) {
        if (status.value.state == VpnConnectionState.CONNECTED ||
            status.value.state == VpnConnectionState.RECONNECTING
        ) {
            LayerVpnService.wakeAfterHandoff(reason)
        }
    }
}
