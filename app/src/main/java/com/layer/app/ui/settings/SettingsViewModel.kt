package com.layer.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.layer.app.BuildConfig
import com.layer.app.data.AdBlockDownloader
import com.layer.app.data.LayerSnapshot
import com.layer.app.data.UpdateCheckResult
import com.layer.app.di.AppContainer
import com.layer.app.vpn.VpnConnectionState
import com.layer.core.config.AutoServerPolicy
import com.layer.core.i18n.copy
import com.layer.core.model.LayerSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class UpdateUiState {
    data object Idle : UpdateUiState()
    data object Checking : UpdateUiState()
    data class Notice(val text: String) : UpdateUiState()
    data class Prompt(val update: UpdateCheckResult.Available) : UpdateUiState()
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val snapshot: StateFlow<LayerSnapshot> = container.repository.snapshot.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        LayerSnapshot(LayerSettings(), emptyList(), emptyList(), false),
    )
    val installedVersion: String = BuildConfig.VERSION_NAME

    private val _updateUi = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateUi: StateFlow<UpdateUiState> = _updateUi.asStateFlow()

    private val _autoCheckEnabled = MutableStateFlow(container.updateChecker.isAutoCheckEnabled())
    val autoCheckEnabled: StateFlow<Boolean> = _autoCheckEnabled.asStateFlow()

    fun setAutoCheckEnabled(enabled: Boolean) {
        container.updateChecker.setAutoCheckEnabled(enabled)
        _autoCheckEnabled.value = enabled
    }

    fun checkForUpdate() {
        if (_updateUi.value is UpdateUiState.Checking) return
        viewModelScope.launch {
            _updateUi.value = UpdateUiState.Checking
            when (val result = container.updateChecker.check(force = true)) {
                is UpdateCheckResult.UpToDate -> {
                    _updateUi.value = UpdateUiState.Notice(
                        copy(
                            "You have the latest version (${result.latest})",
                            "У вас последняя версия (${result.latest})",
                        ),
                    )
                }
                is UpdateCheckResult.Available -> {
                    _updateUi.value = UpdateUiState.Prompt(result)
                }
                is UpdateCheckResult.Failed -> {
                    _updateUi.value = UpdateUiState.Notice(
                        result.message.ifBlank {
                            copy("Could not check for updates.", "Не удалось проверить обновления.")
                        },
                    )
                }
            }
        }
    }

    fun dismissUpdateNotice() {
        _updateUi.value = UpdateUiState.Idle
    }

    fun dismissUpdatePrompt(remember: Boolean, latest: String) {
        if (remember) container.updateChecker.rememberDismissed(latest)
        _updateUi.value = UpdateUiState.Idle
    }

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
                    "[AUTO] event=ui-toggle action=skip reason=too-few-servers " +
                        "count=${settings.servers.size}",
                )
                return@launch
            }
            container.diagnostics.append(
                "[AUTO] event=ui-toggle enabled=$enabled servers=${settings.servers.size} " +
                    "vpn=${container.vpnController.status.value.state}",
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
                "[AUTO] event=ui-interval minutes=${updated.autoSelectIntervalMinutes}",
            )
            container.repository.saveSettings(updated)
            val connected = container.vpnController.status.value.state == VpnConnectionState.CONNECTED
            if (updated.autoSelectServerEnabled && connected) {
                container.autoServerSelector.startMonitoring()
            }
        }
    }

    fun setAdBlock(enabled: Boolean) {
        viewModelScope.launch {
            val settings = snapshot.value.settings
            container.diagnostics.append("[ADS] event=ui-toggle enabled=$enabled")
            container.repository.saveSettings(settings.copy(adBlockEnabled = enabled))
            if (enabled) {
                val fetched = container.adBlockDownloader.ensureCopy(network = null)
                container.diagnostics.append(AdBlockDownloader.logLine(fetched))
            }
            container.vpnController.reload()
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
