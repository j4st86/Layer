package com.layer.app.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.layer.app.R
import com.layer.app.locale.AppLanguage
import com.layer.app.locale.AppLanguagePreferences
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.layer.app.ui.LocalAppContainer
import com.layer.app.ui.components.TonalCard
import com.layer.app.ui.update.UpdateAvailableDialog
import com.layer.app.vpn.BackgroundKeepAlive
import com.layer.app.diagnostics.DiagnosticReport
import com.layer.core.config.AutoServerPolicy
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val container = LocalAppContainer.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val updateUi by viewModel.updateUi.collectAsStateWithLifecycle()
    val autoCheckEnabled by viewModel.autoCheckEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settings = snapshot.settings
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var sharingReport by remember { mutableStateOf(false) }
    var confirmResetRouting by remember { mutableStateOf(false) }
    var confirmResetAll by remember { mutableStateOf(false) }
    var showAlwaysOnInfo by remember { mutableStateOf(false) }
    var showIntervalPicker by remember { mutableStateOf(false) }
    var keepAlive by remember { mutableStateOf(BackgroundKeepAlive.status(context)) }
    var language by remember { mutableStateOf(AppLanguagePreferences.get(context)) }
    val listColors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    val checking = updateUi is UpdateUiState.Checking
    val canAutoSelect = AutoServerPolicy.canEnable(settings.servers.size)
    val autoEnabled = settings.autoSelectServerEnabled && canAutoSelect
    val intervalEnabled = autoEnabled
    LifecycleResumeEffect(Unit) {
        keepAlive = BackgroundKeepAlive.status(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(updateUi) {
        val notice = updateUi as? UpdateUiState.Notice ?: return@LaunchedEffect
        snackbar.showSnackbar(notice.text)
        viewModel.dismissUpdateNotice()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium)
            TonalCard {
                Column {
                    AppLanguage.entries.forEachIndexed { index, option ->
                        if (index > 0) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                        val title = when (option) {
                            AppLanguage.AUTO -> stringResource(R.string.language_auto)
                            AppLanguage.RUSSIAN -> stringResource(R.string.language_russian)
                            AppLanguage.ENGLISH -> stringResource(R.string.language_english)
                        }
                        ListItem(
                            headlineContent = { Text(title) },
                            leadingContent = {
                                RadioButton(
                                    selected = language == option,
                                    onClick = {
                                        language = option
                                        AppLanguagePreferences.set(context, option)
                                    },
                                )
                            },
                            colors = listColors,
                            modifier = Modifier.clickable {
                                language = option
                                AppLanguagePreferences.set(context, option)
                            },
                        )
                    }
                }
            }

            Text(stringResource(R.string.settings_server), style = MaterialTheme.typography.titleMedium)
            TonalCard {
                Column {
                    SettingSwitch(
                        title = stringResource(R.string.settings_auto_select),
                        subtitle = if (canAutoSelect) {
                            stringResource(R.string.settings_auto_select_on)
                        } else {
                            stringResource(R.string.settings_auto_select_need_two)
                        },
                        checked = autoEnabled,
                        enabled = canAutoSelect,
                        onChecked = viewModel::setAutoSelect,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_check_every)) },
                        supportingContent = {
                            Text(
                                pluralStringResource(
                                    R.plurals.minutes_count,
                                    AutoServerPolicy.clampInterval(settings.autoSelectIntervalMinutes),
                                    AutoServerPolicy.clampInterval(settings.autoSelectIntervalMinutes),
                                ),
                            )
                        },
                        colors = if (intervalEnabled) {
                            listColors
                        } else {
                            ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                supportingColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                            )
                        },
                        modifier = Modifier.clickable(
                            enabled = intervalEnabled,
                            onClick = { showIntervalPicker = true },
                        ),
                    )
                }
            }

            Text(stringResource(R.string.settings_routing), style = MaterialTheme.typography.titleMedium)
            TonalCard {
                Column {
                    SettingSwitch(
                        title = stringResource(R.string.settings_auto_lists),
                        subtitle = stringResource(R.string.settings_auto_lists_sub),
                        checked = settings.automaticRuleSetEnabled,
                        onChecked = viewModel::setAutomaticRules,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingSwitch(
                        title = "IPv6",
                        subtitle = stringResource(R.string.settings_ipv6_sub),
                        checked = settings.ipv6Enabled,
                        onChecked = viewModel::setIpv6,
                    )
                }
            }

            Text(stringResource(R.string.settings_stability), style = MaterialTheme.typography.titleMedium)
            StabilityChecklist(
                keepAlive = keepAlive,
                xiaomiFamily = BackgroundKeepAlive.isXiaomiFamily(context),
                onOpenVpnSettings = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
                },
                onAlwaysOnInfo = { showAlwaysOnInfo = true },
                onOpenBatterySettings = { BackgroundKeepAlive.requestFix(context) },
            )

            Text(stringResource(R.string.settings_updates), style = MaterialTheme.typography.titleMedium)
            TonalCard {
                Column {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_check_updates)) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.settings_check_updates_sub,
                                    viewModel.installedVersion,
                                ),
                            )
                        },
                        leadingContent = { Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = null) },
                        trailingContent = {
                            if (checking) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        },
                        colors = listColors,
                        modifier = Modifier.clickable(enabled = !checking, onClick = viewModel::checkForUpdate),
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingSwitch(
                        title = stringResource(R.string.settings_auto_updates),
                        subtitle = stringResource(R.string.settings_auto_updates_sub),
                        checked = autoCheckEnabled,
                        onChecked = viewModel::setAutoCheckEnabled,
                    )
                }
            }

            TonalCard {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_logs)) },
                    supportingContent = { Text(stringResource(R.string.settings_logs_sub)) },
                    leadingContent = { Icon(Icons.Outlined.BugReport, contentDescription = null) },
                    colors = listColors,
                    modifier = Modifier.clickable(enabled = !sharingReport) {
                        sharingReport = true
                        scope.launch {
                            val result = try {
                                DiagnosticReport.share(context, container)
                            } finally {
                                sharingReport = false
                            }
                            snackbar.showSnackbar(
                                result.fold(
                                    onSuccess = { context.getString(R.string.report_saved, it) },
                                    onFailure = { it.message ?: context.getString(R.string.report_failed) },
                                ),
                            )
                        }
                    },
                )
            }

            Text(stringResource(R.string.settings_reset), style = MaterialTheme.typography.titleMedium)
            TonalCard {
                Column(Modifier.padding(bottom = 8.dp)) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_reset_rules)) },
                        supportingContent = { Text(stringResource(R.string.settings_reset_rules_sub)) },
                        leadingContent = { Icon(Icons.Outlined.RestartAlt, contentDescription = null) },
                        colors = listColors,
                    )
                    OutlinedButton(
                        onClick = { confirmResetRouting = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) { Text(stringResource(R.string.settings_reset_rules_button)) }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_reset_all)) },
                        supportingContent = { Text(stringResource(R.string.settings_reset_all_sub)) },
                        leadingContent = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
                        colors = listColors,
                    )
                    Button(
                        onClick = { confirmResetAll = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) { Text(stringResource(R.string.settings_reset_all_button)) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showIntervalPicker) {
        AlertDialog(
            onDismissRequest = { showIntervalPicker = false },
            title = { Text(stringResource(R.string.settings_check_every)) },
            text = {
                Column {
                    AutoServerPolicy.intervalMinutes.forEach { minutes ->
                        ListItem(
                            headlineContent = {
                                Text(pluralStringResource(R.plurals.minutes_count, minutes, minutes))
                            },
                            leadingContent = {
                                RadioButton(
                                    selected = minutes == settings.autoSelectIntervalMinutes,
                                    onClick = {
                                        viewModel.setAutoSelectInterval(minutes)
                                        showIntervalPicker = false
                                    },
                                )
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                            modifier = Modifier.clickable {
                                viewModel.setAutoSelectInterval(minutes)
                                showIntervalPicker = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showIntervalPicker = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    if (confirmResetRouting) {
        AlertDialog(
            onDismissRequest = { confirmResetRouting = false },
            title = { Text(stringResource(R.string.settings_reset_rules_title)) },
            text = { Text(stringResource(R.string.settings_reset_rules_body)) },
            confirmButton = {
                val rulesReset = stringResource(R.string.rules_reset)
                TextButton(onClick = {
                    viewModel.resetRouting()
                    confirmResetRouting = false
                    scope.launch { snackbar.showSnackbar(rulesReset) }
                }) { Text(stringResource(R.string.action_reset)) }
            },
            dismissButton = { TextButton(onClick = { confirmResetRouting = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (showAlwaysOnInfo) {
        AlertDialog(
            onDismissRequest = { showAlwaysOnInfo = false },
            title = { Text("Always-on VPN") },
            text = { Text(stringResource(R.string.always_on_info_body)) },
            confirmButton = {
                TextButton(onClick = { showAlwaysOnInfo = false }) { Text(stringResource(R.string.action_got_it)) }
            },
        )
    }
    if (confirmResetAll) {
        AlertDialog(
            onDismissRequest = { confirmResetAll = false },
            title = { Text(stringResource(R.string.settings_reset_all_title)) },
            text = { Text(stringResource(R.string.settings_reset_all_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetAll()
                    confirmResetAll = false
                }) { Text(stringResource(R.string.settings_reset_all)) }
            },
            dismissButton = { TextButton(onClick = { confirmResetAll = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    val updatePrompt = updateUi as? UpdateUiState.Prompt
    if (updatePrompt != null) {
        UpdateAvailableDialog(
            update = updatePrompt.update,
            onDismiss = { viewModel.dismissUpdatePrompt(remember = false, latest = updatePrompt.update.latest) },
            onLater = { viewModel.dismissUpdatePrompt(remember = true, latest = updatePrompt.update.latest) },
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val colors = if (enabled) {
        ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    } else {
        ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
        )
    }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
        },
        colors = colors,
    )
}

@Composable
private fun StabilityChecklist(
    keepAlive: BackgroundKeepAlive.Status,
    xiaomiFamily: Boolean,
    onOpenVpnSettings: () -> Unit,
    onAlwaysOnInfo: () -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    TonalCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            AlwaysOnRow(
                onOpenSettings = onOpenVpnSettings,
                onInfo = onAlwaysOnInfo,
            )
            HorizontalDivider()
            ChecklistRow(
                ok = keepAlive == BackgroundKeepAlive.Status.UNRESTRICTED,
                title = stringResource(R.string.background_usage),
                subtitle = when (keepAlive) {
                    BackgroundKeepAlive.Status.UNRESTRICTED -> stringResource(
                        if (xiaomiFamily) R.string.battery_unrestricted_xiaomi
                        else R.string.battery_unrestricted,
                    )
                    BackgroundKeepAlive.Status.OPTIMIZED -> stringResource(
                        if (xiaomiFamily) R.string.battery_optimized_xiaomi
                        else R.string.battery_optimized,
                    )
                    BackgroundKeepAlive.Status.RESTRICTED -> stringResource(
                        if (xiaomiFamily) R.string.battery_restricted_xiaomi
                        else R.string.battery_restricted,
                    )
                },
                actionLabel = stringResource(R.string.battery_settings),
                onAction = onOpenBatterySettings,
            )
        }
    }
}

@Composable
private fun AlwaysOnRow(
    onOpenSettings: () -> Unit,
    onInfo: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Always-on VPN", style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.always_on_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.vpn_settings))
            }
            IconButton(onClick = onInfo) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.why_needed),
                )
            }
        }
    }
}

@Composable
private fun ChecklistRow(
    ok: Boolean,
    title: String,
    subtitle: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val iconTint = if (ok) colors.primary else colors.error
    val iconBackground = iconTint.copy(alpha = 0.12f)
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(iconBackground, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (ok) Icons.Filled.Check else Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = iconTint,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            if (!ok) {
                FilledTonalButton(
                    onClick = onAction,
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text(actionLabel) }
            }
        }
    }
}
