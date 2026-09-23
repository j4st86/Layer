package com.layer.app.ui.ads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.layer.app.data.InstalledApp
import com.layer.app.di.AppContainer
import com.layer.core.model.AdBlockApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AdsViewModel(private val container: AppContainer) : ViewModel() {
    private val pickerQuery = MutableStateFlow("")
    private val installed = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val _undo = MutableStateFlow<AdBlockApp?>(null)
    val undo: StateFlow<AdBlockApp?> = _undo

    val apps: StateFlow<List<AdBlockApp>> = container.repository.adBlockApps.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val pickerApps: StateFlow<List<InstalledApp>> = combine(installed, pickerQuery, apps) { all, q, current ->
        val added = current.mapTo(HashSet()) { it.packageName }
        all.filter { app ->
            app.packageName !in added &&
                (q.isBlank() || app.label.contains(q, ignoreCase = true) || app.packageName.contains(q, ignoreCase = true))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pickerSearchQuery: StateFlow<String> = pickerQuery

    init {
        viewModelScope.launch {
            installed.value = container.repository.installedApps()
        }
    }

    fun onPickerQuery(value: String) {
        pickerQuery.value = value
    }

    fun resetPickerQuery() {
        pickerQuery.value = ""
    }

    fun add(app: InstalledApp) {
        viewModelScope.launch {
            container.repository.upsertAdBlockApp(AdBlockApp(app.packageName, app.label))
            container.vpnController.reload()
        }
    }

    fun remove(app: AdBlockApp) {
        viewModelScope.launch {
            _undo.value = container.repository.removeAdBlockApp(app.packageName)
            container.vpnController.reload()
        }
    }

    fun undoRemove() {
        val app = _undo.value ?: return
        viewModelScope.launch {
            container.repository.restoreAdBlockApp(app)
            _undo.value = null
            container.vpnController.reload()
        }
    }

    fun consumeUndo() {
        _undo.value = null
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AdsViewModel(container) as T
        }
    }
}
