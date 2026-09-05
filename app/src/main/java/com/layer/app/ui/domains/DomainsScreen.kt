package com.layer.app.ui.domains

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.layer.app.R
import com.layer.app.ui.LocalAppContainer
import com.layer.app.ui.components.DomainModeSelector
import com.layer.app.ui.components.EmptyState
import com.layer.core.config.AutomaticRuleSet
import com.layer.core.model.DomainRoutingMode
import com.layer.core.model.DomainRoutingRule
import com.layer.core.routing.HostnameNormalizer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DomainsScreen() {
    val container = LocalAppContainer.current
    val viewModel: DomainsViewModel = viewModel(factory = DomainsViewModel.factory(container))
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val undo by viewModel.undo.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showAdd by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }

    LaunchedEffect(undo) {
        val rule = undo ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(
            context.getString(R.string.rule_removed),
            context.getString(R.string.action_undo),
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoRemove() else viewModel.consumeUndo()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.domains_title)) },
                actions = {
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = stringResource(R.string.why_needed))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.action_add)) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (rules.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Outlined.Language,
                        stringResource(R.string.domains_empty_title),
                        stringResource(R.string.domains_empty_body),
                    )
                }
            }
            items(rules, key = { it.domain }) { rule ->
                DomainRuleRow(rule, viewModel::setMode) { viewModel.remove(rule) }
            }
            item { androidx.compose.foundation.layout.Spacer(Modifier.size(88.dp)) }
        }
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text(stringResource(R.string.domains_help_title)) },
            text = { DomainHelpText(viewModel.automatic) },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) { Text(stringResource(R.string.action_got_it)) }
            },
        )
    }

    if (showAdd) {
        AddDomainDialog(
            error = error,
            onDismiss = { showAdd = false; viewModel.consumeError() },
            onDraftChange = viewModel::onDraftChange,
            onAdd = { raw, mode -> if (viewModel.add(raw, mode)) showAdd = false },
        )
    }
}

@Composable
private fun DomainHelpText(sets: List<AutomaticRuleSet>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.domains_help_body))
        Text(
            stringResource(R.string.domains_lists_heading),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            sets.joinToString("\n") { it.title },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DomainRuleRow(
    rule: DomainRoutingRule,
    onMode: (DomainRoutingRule, DomainRoutingMode) -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            ListItem(
                headlineContent = { Text(HostnameNormalizer.display(rule.domain)) },
                trailingContent = {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            DomainModeSelector(
                rule.mode,
                Modifier.padding(horizontal = 16.dp),
            ) { onMode(rule, it) }
        }
    }
}

@Composable
private fun AddDomainDialog(
    error: String?,
    onDismiss: () -> Unit,
    onDraftChange: (String) -> Unit,
    onAdd: (String, DomainRoutingMode) -> Unit,
) {
    var raw by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(DomainRoutingMode.VPN) }
    var submitted by remember { mutableStateOf(false) }
    val normalized = HostnameNormalizer.normalize(raw)
    val fieldError = if ((error != null || submitted) && !normalized.isValid) {
        normalized.error
    } else {
        null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_domain)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = raw,
                    onValueChange = {
                        raw = it
                        onDraftChange(it)
                    },
                    placeholder = { Text(stringResource(R.string.domain_placeholder)) },
                    singleLine = true,
                    isError = fieldError != null,
                    supportingText = fieldError?.let { message ->
                        { Text(message) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                DomainModeSelector(mode) { mode = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    submitted = true
                    onAdd(raw, mode)
                },
            ) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
