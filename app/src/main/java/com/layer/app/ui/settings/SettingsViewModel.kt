package com.layer.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.layer.app.data.LayerSnapshot
import com.layer.app.di.AppContainer
import com.layer.app.vpn.VpnConnectionState
import com.layer.core.config.AutoServerPolicy
import com.layer.core.i18n.copy
import com.layer.core.model.LayerSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val snapshot: StateFlow<LayerSnapshot> = container.repository.snapshot.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        LayerSnapshot(LayerSettings(), emptyList(), emptyList(), false),
    )

    fun setAutomaticRules(enabled: Boolean) {
        viewModelScope.launch {
            container.repository.saveSettings(
                snapshot.value.settings.copy(automaticRuleSetEnabled = enabled),
            )
            container.vpnController.reload()
        }
    }

    fun setIpv6(enabled: Boolean) {
        viewModelScope.launch {
            container.repository.saveSettings(snapshot.value.settings.copy(ipv6Enabled = enabled))
            container.vpnController.reload()
        }
    }

    fun setAutoSelect(enabled: Boolean) {
        viewModelScope.launch {
            val settings = snapshot.value.settings
            if (enabled && !AutoServerPolicy.canEnable(settings.servers.size)) {
                container.diagnostics.append(
                    "[AUTO] UI " + copy(
                        "cannot enable: servers ${settings.servers.size}",
                        "включить нельзя: серверов ${settings.servers.size}",
                    ),
                )
                return@launch
            }
            container.diagnostics.append(
                "[AUTO] UI ${copy(if (enabled) "on" else "off", if (enabled) "включён" else "выключен")}, " +
                    "servers=${settings.servers.size}, VPN=${container.vpnController.status.value.state}",
            )
            container.repository.saveSettings(settings.copy(autoSelectServerEnabled = enabled))
            if (enabled) {
                if (container.vpnController.status.value.state == VpnConnectionState.CONNECTED) {
                    container.autoServerSelector.notifyEnabledWhileConnected()
                }
            } else {
                container.autoServerSelector.stopMonitoring()
            }
        }
    }

    fun setAutoSelectInterval(minutes: Int) {
        viewModelScope.launch {
            val updated = snapshot.value.settings.copy(
                autoSelectIntervalMinutes = AutoServerPolicy.clampInterval(minutes),
            )
            container.diagnostics.append(
                "[AUTO] UI " + copy(
                    "interval ${updated.autoSelectIntervalMinutes} min",
                    "интервал ${updated.autoSelectIntervalMinutes} мин",
                ),
            )
            container.repository.saveSettings(updated)
            val connected = container.vpnController.status.value.state == VpnConnectionState.CONNECTED
            if (updated.autoSelectServerEnabled && connected) {
                container.autoServerSelector.startMonitoring()
            }
        }
    }

    fun resetRouting() {
        viewModelScope.launch {
            container.repository.resetRouting()
            container.vpnController.reload()
        }
    }

    fun resetAll() {
        viewModelScope.launch {
            container.vpnController.disconnect()
            container.repository.resetAll()
        }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(container) as T
        }
    }
}
