package com.layer.app.ui.apps

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
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.layer.app.R
import com.layer.app.data.InstalledApp
import com.layer.app.ui.LocalAppContainer
import com.layer.app.ui.components.AppModeSelector
import com.layer.app.ui.components.EmptyState
import com.layer.core.model.AppRoutingMode
import com.layer.core.model.AppRoutingRule

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen() {
    val container = LocalAppContainer.current
    val viewModel: AppsViewModel = viewModel(factory = AppsViewModel.factory(container))
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val pickerQuery by viewModel.pickerSearchQuery.collectAsStateWithLifecycle()
    val pickerApps by viewModel.pickerApps.collectAsStateWithLifecycle()
    val undo by viewModel.undo.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val removedMessage = stringResource(R.string.rule_removed)
    val undoLabel = stringResource(R.string.action_undo)
    var showPicker by remember { mutableStateOf(false) }
    var pendingApp by remember { mutableStateOf<InstalledApp?>(null) }
    var showHelp by remember { mutableStateOf(false) }

    LaunchedEffect(undo, removedMessage, undoLabel) {
        val rule = undo ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(
            removedMessage,
            undoLabel,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoRemove() else viewModel.consumeUndo()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.apps_title)) },
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
                onClick = {
                    viewModel.resetPickerQuery()
                    showPicker = true
                },
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
                        Icons.Outlined.Apps,
                        stringResource(R.string.apps_empty_title),
                        stringResource(R.string.apps_empty_body),
                    )
                }
            }
            items(rules, key = { it.packageName }) { rule ->
                AppRuleRow(rule, viewModel::setMode) { viewModel.remove(rule) }
            }
            item { SpacerBottom() }
        }
    }

    if (showPicker) {
        AppPickerSheet(
            title = stringResource(R.string.apps_add),
            query = pickerQuery,
            onQueryChange = viewModel::onPickerQuery,
            searchPlaceholder = stringResource(R.string.apps_search),
            apps = pickerApps,
            onDismiss = { showPicker = false },
            onPick = { app ->
                pendingApp = app
                showPicker = false
            },
        )
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text(stringResource(R.string.apps_help_title)) },
            text = { Text(stringResource(R.string.apps_help_body)) },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) { Text(stringResource(R.string.action_got_it)) }
            },
        )
    }

    val app = pendingApp
    if (app != null) {
        AlertDialog(
            onDismissRequest = { pendingApp = null },
            title = { Text(app.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.apps_how_to_route), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AppModeSelector(selected = null) { mode ->
                        viewModel.setMode(app, mode)
                        pendingApp = null
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pendingApp = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun AppRuleRow(
    rule: AppRoutingRule,
    onMode: (AppRoutingRule, AppRoutingMode) -> Unit,
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
                headlineContent = { Text(rule.appName) },
                trailingContent = {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.delete_rule),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            AppModeSelector(
                rule.mode,
                Modifier.padding(horizontal = 16.dp),
            ) { onMode(rule, it) }
        }
    }
}

@Composable
private fun SpacerBottom() {
    androidx.compose.foundation.layout.Spacer(Modifier.size(88.dp))
}
