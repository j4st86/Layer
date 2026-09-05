package com.layer.app.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.layer.app.data.InstalledApp
import com.layer.app.di.AppContainer
import com.layer.core.model.AppRoutingMode
import com.layer.core.model.AppRoutingRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppsViewModel(private val container: AppContainer) : ViewModel() {
    private val pickerQuery = MutableStateFlow("")
    private val installed = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val _undo = MutableStateFlow<AppRoutingRule?>(null)
    val undo: StateFlow<AppRoutingRule?> = _undo

    val rules: StateFlow<List<AppRoutingRule>> = container.repository.appRules.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val pickerApps: StateFlow<List<InstalledApp>> = combine(installed, pickerQuery) { apps, q ->
        if (q.isBlank()) apps
        else apps.filter { it.label.contains(q, true) || it.packageName.contains(q, true) }
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

    fun setMode(app: InstalledApp, mode: AppRoutingMode) {
        viewModelScope.launch {
            container.repository.upsertAppRule(
                AppRoutingRule(app.packageName, app.label, mode),
            )
            container.vpnController.reload()
        }
    }

    fun setMode(rule: AppRoutingRule, mode: AppRoutingMode) {
        viewModelScope.launch {
            container.repository.upsertAppRule(rule.copy(mode = mode))
            container.vpnController.reload()
        }
    }

    fun remove(rule: AppRoutingRule) {
        viewModelScope.launch {
            _undo.value = container.repository.removeAppRule(rule.packageName)
            container.vpnController.reload()
        }
    }

    fun undoRemove() {
        val rule = _undo.value ?: return
        viewModelScope.launch {
            container.repository.restoreAppRule(rule)
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
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppsViewModel(container) as T
        }
    }
}
