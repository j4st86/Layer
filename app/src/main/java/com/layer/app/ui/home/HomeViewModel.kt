package com.layer.app.ui.home

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.layer.app.data.LayerSnapshot
import com.layer.app.di.AppContainer
import com.layer.app.vpn.VpnUiStatus
import com.layer.core.model.LayerSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    val snapshot: StateFlow<LayerSnapshot> = container.repository.snapshot.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        LayerSnapshot(LayerSettings(), emptyList(), emptyList(), false),
    )
    val status: StateFlow<VpnUiStatus> = container.vpnController.status

    private val _recommendAppsPrompt = MutableStateFlow(false)
    val recommendAppsPrompt: StateFlow<Boolean> = _recommendAppsPrompt.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = container.repository.settings.first { it.servers.isNotEmpty() }
            if (settings.recommendedAppsPromptDone) return@launch
            _recommendAppsPrompt.value = runCatching {
                container.repository.recommendedAppRulesToAdd()
            }.getOrDefault(emptyList()).isNotEmpty()
        }
    }

    fun prepareVpn(): Intent? = container.vpnController.prepare()

    suspend fun connect() {
        container.vpnController.connect()
    }

    fun disconnect() {
        container.vpnController.disconnect()
    }

    fun retestPing() {
        container.connectionPing.retest()
    }

    fun importConnection(raw: String, onResult: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            val result = container.repository.importConnection(raw)
            if (result.isSuccess) container.vpnController.reload()
            onResult(result)
        }
    }

    fun refreshSubscription(id: String, onResult: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            val result = container.repository.refreshSubscription(id)
            if (result.isSuccess) container.vpnController.reload()
            onResult(result)
        }
    }

    fun selectServer(id: String) {
        viewModelScope.launch {
            val result = container.repository.selectServer(id)
            if (result.isSuccess) container.vpnController.reload()
        }
    }

    fun updateServer(
        id: String,
        name: String,
        address: String,
        port: Int,
        sni: String,
        flow: String,
        fingerprint: String,
        alpn: String,
        note: String,
        onResult: (Result<Unit>) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result = container.repository.updateServer(
                id, name, address, port, sni, flow, fingerprint, alpn, note,
            )
            if (result.isSuccess) container.vpnController.reload()
            onResult(result)
        }
    }

    fun updateSubscriptionNote(id: String, note: String, onResult: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            onResult(container.repository.updateSubscriptionNote(id, note))
        }
    }

    fun deleteServer(id: String) {
        viewModelScope.launch {
            val result = container.repository.deleteServer(id)
            if (result.isSuccess) container.vpnController.reload()
        }
    }

    fun deleteSubscription(id: String) {
        viewModelScope.launch {
            val result = container.repository.deleteSubscription(id)
            if (result.isSuccess) container.vpnController.reload()
        }
    }

    fun acceptRecommendedApps() {
        _recommendAppsPrompt.value = false
        viewModelScope.launch {
            container.repository.applyRecommendedApps()
            container.vpnController.reload()
        }
    }

    fun declineRecommendedApps() {
        _recommendAppsPrompt.value = false
        viewModelScope.launch {
            container.repository.declineRecommendedApps()
        }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return HomeViewModel(container) as T
            }
        }
    }
}
