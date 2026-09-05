package com.layer.app.ui.domains

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.layer.app.di.AppContainer
import com.layer.core.config.AutomaticRuleSet
import com.layer.core.config.RuleSetCatalog
import com.layer.core.model.DomainRoutingMode
import com.layer.core.model.DomainRoutingRule
import com.layer.core.routing.HostnameNormalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DomainsViewModel(private val container: AppContainer) : ViewModel() {
    private val _undo = MutableStateFlow<DomainRoutingRule?>(null)
    private val _error = MutableStateFlow<String?>(null)

    val undo: StateFlow<DomainRoutingRule?> = _undo
    val error: StateFlow<String?> = _error
    val automatic: List<AutomaticRuleSet> = RuleSetCatalog.vpnLists

    val rules: StateFlow<List<DomainRoutingRule>> = container.repository.domainRules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(raw: String, mode: DomainRoutingMode): Boolean {
        val normalized = HostnameNormalizer.normalize(raw)
        if (!normalized.isValid) {
            _error.value = normalized.error
            return false
        }
        _error.value = null
        viewModelScope.launch {
            container.repository.upsertDomainRule(DomainRoutingRule(normalized.domain, mode))
                .onFailure { _error.value = it.message }
                .onSuccess {
                    _error.value = null
                    container.vpnController.reload()
                }
        }
        return true
    }

    fun setMode(rule: DomainRoutingRule, mode: DomainRoutingMode) {
        viewModelScope.launch {
            container.repository.upsertDomainRule(rule.copy(mode = mode))
            container.vpnController.reload()
        }
    }

    fun remove(rule: DomainRoutingRule) {
        viewModelScope.launch {
            _undo.value = container.repository.removeDomainRule(rule.domain)
            container.vpnController.reload()
        }
    }

    fun undoRemove() {
        val rule = _undo.value ?: return
        viewModelScope.launch {
            container.repository.restoreDomainRule(rule)
            _undo.value = null
            container.vpnController.reload()
        }
    }

    fun consumeUndo() {
        _undo.value = null
    }

    fun consumeError() {
        _error.value = null
    }

    fun onDraftChange(raw: String) {
        if (_error.value != null && HostnameNormalizer.normalize(raw).isValid) {
            _error.value = null
        }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DomainsViewModel(container) as T
        }
    }
}
