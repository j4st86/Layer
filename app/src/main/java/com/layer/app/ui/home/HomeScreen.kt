package com.layer.app.ui.home

import android.Manifest
import android.app.Activity
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.layer.app.R
import com.layer.app.diagnostics.DiagnosticReport
import com.layer.app.ui.LocalAppContainer
import com.layer.app.ui.components.TonalCard
import com.layer.app.ui.settings.ConnectionProfilesSection
import com.layer.app.vpn.BackgroundKeepAlive
import com.layer.app.vpn.NotificationPermission
import com.layer.app.vpn.VpnConnectionState
import com.layer.app.vpn.VpnUiStatus
import com.layer.core.config.AutoServerPolicy
import com.layer.core.config.DuplicateConnectionException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val container = LocalAppContainer.current
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val recommendAppsPrompt by viewModel.recommendAppsPrompt.collectAsStateWithLifecycle()
    val pingStatus by container.connectionPing.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var addUi by remember { mutableStateOf<AddConnectionUi>(AddConnectionUi.Closed) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch { viewModel.connect() }
        }
    }
    fun continueToVpnConsent() {
        val prepare = viewModel.prepareVpn()
        if (prepare != null) permissionLauncher.launch(prepare)
        else scope.launch { viewModel.connect() }
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { continueToVpnConsent() }
    fun startConnect() {
        if (!NotificationPermission.isGranted(context)) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        continueToVpnConsent()
    }
    val connected = status.state == VpnConnectionState.CONNECTED
    val busy = status.state == VpnConnectionState.CONNECTING ||
        status.state == VpnConnectionState.RECONNECTING
    val hasServer = snapshot.settings.servers.isNotEmpty() && snapshot.hasUuid
    var keepAlive by remember { mutableStateOf(BackgroundKeepAlive.status(context)) }
    LifecycleResumeEffect(Unit) {
        keepAlive = BackgroundKeepAlive.status(context)
        onPauseOrDispose { }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { },
                actions = {
                    IconButton(onClick = { addUi = AddConnectionUi.Choose }) {
                        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.add_server_or_subscription))
                    }
                },
            )
        },
        snackbarHost = {
            Column {
                if (keepAlive != BackgroundKeepAlive.Status.UNRESTRICTED) {
                    UnrestrictedBatterySnackbar(
                        onOpenSettings = { BackgroundKeepAlive.requestFix(context) },
                    )
                }
                if (recommendAppsPrompt) {
                    RecommendedAppsSnackbar(
                        onAdd = viewModel::acceptRecommendedApps,
                        onCancel = viewModel::declineRecommendedApps,
                    )
                }
                SnackbarHost(snackbar)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StatusHero(
                    status = status,
                    serverName = snapshot.settings.activeConnectionName(),
                    autoEnabled = snapshot.settings.autoSelectServerEnabled,
                    autoVisible = AutoServerPolicy.canEnable(snapshot.settings.servers.size),
                    latencyMs = pingStatus.latencyMs,
                    probing = pingStatus.probing,
                    onPingClick = viewModel::retestPing,
                )
                if (status.errorTitle != null) {
                    TonalCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                status.errorTitle ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                status.errorDetails.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FilledTonalButton(
                                onClick = {
                                    scope.launch { DiagnosticReport.share(context, container) }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.send_report)) }
                        }
                    }
                }
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = !busy && (connected || hasServer),
                    onClick = {
                        if (connected || busy) {
                            viewModel.disconnect()
                        } else {
                            startConnect()
                        }
                    },
                    colors = if (connected) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) {
                    Text(
                        when {
                            connected -> stringResource(R.string.action_disconnect)
                            busy -> status.message
                            else -> stringResource(R.string.action_connect)
                        },
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ConnectionProfilesSection(
                    settings = snapshot.settings,
                    onRefreshSubscription = { id, done -> viewModel.refreshSubscription(id, done) },
                    onSelectServer = viewModel::selectServer,
                    onUpdateServer = { id, name, address, port, sni, flow, fingerprint, alpn, note, done ->
                        viewModel.updateServer(id, name, address, port, sni, flow, fingerprint, alpn, note, done)
                    },
                    onUpdateSubscriptionNote = { id, note, done ->
                        viewModel.updateSubscriptionNote(id, note, done)
                    },
                    onDeleteServer = viewModel::deleteServer,
                    onDeleteSubscription = viewModel::deleteSubscription,
                    onMessage = { text -> scope.launch { snackbar.showSnackbar(text) } },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    when (val step = addUi) {
        AddConnectionUi.Closed -> Unit
        AddConnectionUi.Choose -> AddMethodChooser(
            onQr = {
                addUi = AddConnectionUi.Closed
                scanConnectionQr(context) { scanned ->
                    scanned.fold(
                        onSuccess = { payload ->
                            viewModel.importConnection(payload) { imported ->
                                when {
                                    imported.isSuccess -> {
                                        scope.launch { snackbar.showSnackbar(addedConnectionMessage(context, payload)) }
                                    }
                                    imported.exceptionOrNull() is DuplicateConnectionException -> {
                                        scope.launch {
                                            snackbar.showSnackbar(DuplicateConnectionException.MESSAGE)
                                        }
                                    }
                                    else -> {
                                        addUi = AddConnectionUi.Link(
                                            initial = payload,
                                            error = imported.exceptionOrNull()?.message,
                                        )
                                    }
                                }
                            }
                        },
                        onFailure = { error ->
                            val message = qrScanUserMessage(context, error) ?: return@fold
                            scope.launch { snackbar.showSnackbar(message) }
                            addUi = AddConnectionUi.Choose
                        },
                    )
                }
            },
            onLink = { addUi = AddConnectionUi.Link() },
            onDismiss = { addUi = AddConnectionUi.Closed },
        )
        is AddConnectionUi.Link -> AddLinkDialog(
            initial = step.initial,
            initialError = step.error,
            onDismiss = { addUi = AddConnectionUi.Closed },
            onConfirm = { value, done ->
                viewModel.importConnection(value) { result ->
                    when {
                        result.isSuccess -> {
                            addUi = AddConnectionUi.Closed
                            scope.launch { snackbar.showSnackbar(addedConnectionMessage(context, value)) }
                            done(result)
                        }
                        result.exceptionOrNull() is DuplicateConnectionException -> {
                            addUi = AddConnectionUi.Closed
                            scope.launch { snackbar.showSnackbar(DuplicateConnectionException.MESSAGE) }
                            done(Result.success(Unit))
                        }
                        else -> done(result)
                    }
                }
            },
        )
    }
}

private fun addedConnectionMessage(context: Context, raw: String): String {
    val value = raw.trim()
    return if (value.startsWith("http://", ignoreCase = true) ||
        value.startsWith("https://", ignoreCase = true)
    ) {
        context.getString(R.string.subscription_added)
    } else {
        context.getString(R.string.server_added)
    }
}

@Composable
private fun StatusHero(
    status: VpnUiStatus,
    serverName: String,
    autoEnabled: Boolean,
    autoVisible: Boolean,
    latencyMs: Long?,
    probing: Boolean,
    onPingClick: () -> Unit,
) {
    val connected = status.state == VpnConnectionState.CONNECTED
    val fallbackConnected = stringResource(R.string.status_connected)
    val connecting = stringResource(R.string.status_connecting)
    val reconnecting = stringResource(R.string.status_reconnecting)
    val errorTitle = stringResource(R.string.status_error)
    val disconnected = stringResource(R.string.status_disconnected)
    val (icon, title, color) = when (status.state) {
        VpnConnectionState.CONNECTED -> Triple(
            Icons.Rounded.CheckCircle,
            serverName.ifBlank { fallbackConnected },
            MaterialTheme.colorScheme.primary,
        )
        VpnConnectionState.CONNECTING -> Triple(
            Icons.Rounded.Sync,
            connecting,
            MaterialTheme.colorScheme.tertiary,
        )
        VpnConnectionState.RECONNECTING -> Triple(
            Icons.Rounded.Sync,
            reconnecting,
            MaterialTheme.colorScheme.tertiary,
        )
        VpnConnectionState.ERROR -> Triple(
            Icons.Rounded.Error,
            errorTitle,
            MaterialTheme.colorScheme.error,
        )
        VpnConnectionState.DISCONNECTED -> Triple(
            Icons.Rounded.CloudOff,
            disconnected,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val pingText = when {
        status.state == VpnConnectionState.DISCONNECTED ||
            status.state == VpnConnectionState.ERROR -> "—"
        probing -> "…"
        latencyMs != null -> "$latencyMs ms"
        else -> "—"
    }
    val pingClickable = connected && !probing
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusBubble(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            StatusBubble(
                modifier = Modifier.fillMaxHeight(),
                onClick = onPingClick,
                clickEnabled = pingClickable,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "999 ms",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.alpha(0f),
                    )
                    Text(
                        pingText,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (pingText == "—" || pingText == "…") {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        if (autoVisible) {
            StatusBubble(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = if (autoEnabled) Icons.Filled.Check else Icons.Filled.Close,
                        contentDescription = null,
                        tint = if (autoEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        stringResource(R.string.auto_select_server),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBubble(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    clickEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (onClick != null) {
        Surface(
            onClick = onClick,
            enabled = clickEnabled,
            modifier = modifier,
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            content = content,
        )
    } else {
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            content = content,
        )
    }
}

@Composable
private fun UnrestrictedBatterySnackbar(
    onOpenSettings: () -> Unit,
) {
    Snackbar(
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp),
        action = {
            Button(
                onClick = onOpenSettings,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.inverseOnSurface,
                    contentColor = MaterialTheme.colorScheme.inverseSurface,
                ),
            ) { Text(stringResource(R.string.action_open)) }
        },
    ) {
        Text(stringResource(R.string.battery_snackbar))
    }
}

@Composable
private fun RecommendedAppsSnackbar(
    onAdd: () -> Unit,
    onCancel: () -> Unit,
) {
    Snackbar(
        modifier = Modifier.padding(12.dp),
        action = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.72f),
                    ),
                ) { Text(stringResource(R.string.action_cancel)) }
                Button(
                    onClick = onAdd,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.inverseOnSurface,
                        contentColor = MaterialTheme.colorScheme.inverseSurface,
                    ),
                ) { Text(stringResource(R.string.action_add)) }
            }
        },
    ) {
        Text(stringResource(R.string.recommended_apps_prompt))
    }
}
