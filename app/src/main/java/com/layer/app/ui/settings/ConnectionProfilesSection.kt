package com.layer.app.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.layer.app.R
import com.layer.app.ui.components.TonalCard
import com.layer.core.model.LayerSettings
import com.layer.core.model.SavedServer
import com.layer.core.model.SavedSubscription

@Composable
fun ConnectionProfilesSection(
    settings: LayerSettings,
    onRefreshSubscription: (String, (Result<Unit>) -> Unit) -> Unit,
    onSelectServer: (String) -> Unit,
    onUpdateServer: (String, String, String, Int, String, String, String, String, String, (Result<Unit>) -> Unit) -> Unit,
    onUpdateSubscriptionNote: (String, String, (Result<Unit>) -> Unit) -> Unit,
    onDeleteServer: (String) -> Unit,
    onDeleteSubscription: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    var editing by remember { mutableStateOf<SavedServer?>(null) }
    var editingSubscription by remember { mutableStateOf<SavedSubscription?>(null) }
    var deletingServer by remember { mutableStateOf<SavedServer?>(null) }
    var deletingSubscription by remember { mutableStateOf<SavedSubscription?>(null) }
    val listColors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    val context = LocalContext.current
    val manual = settings.manualServers()

    Text(stringResource(R.string.servers), style = MaterialTheme.typography.titleMedium)
    if (manual.isEmpty() && settings.subscriptions.isEmpty()) {
        TonalCard {
            ListItem(
                headlineContent = { Text(stringResource(R.string.no_servers_yet)) },
                supportingContent = { Text(stringResource(R.string.no_servers_hint)) },
                colors = listColors,
            )
        }
    }
    if (manual.isNotEmpty()) {
        TonalCard {
            Column {
                manual.forEachIndexed { index, server ->
                    if (index > 0) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    ServerRow(
                        server = server,
                        selected = server.id == settings.activeServerId,
                        colors = listColors,
                        onSelect = { onSelectServer(server.id) },
                        onInfo = { editing = server },
                        onDelete = { deletingServer = server },
                    )
                }
            }
        }
    }

    settings.subscriptions.forEach { subscription ->
        val grouped = settings.serversFor(subscription.id)
        val defaultExpanded = grouped.any { it.id == settings.activeServerId }
        val expanded = expandedGroups[subscription.id] ?: defaultExpanded
        TonalCard {
            Column(Modifier.animateContentSize()) {
                GroupHeader(
                    title = subscription.visibleName(),
                    subtitle = groupSubtitle(grouped, settings.activeServerId, expanded),
                    expanded = expanded,
                    colors = listColors,
                    onToggle = { expandedGroups[subscription.id] = !expanded },
                    trailing = {
                        Row {
                            IconButton(onClick = { editingSubscription = subscription }) {
                                Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.subscription_params))
                            }
                            IconButton(
                                onClick = {
                                    onRefreshSubscription(subscription.id) { result ->
                                        onMessage(
                                            if (result.isSuccess) context.getString(R.string.subscription_updated)
                                            else result.exceptionOrNull()?.message
                                                ?: context.getString(R.string.subscription_update_failed),
                                        )
                                    }
                                },
                            ) {
                                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.refresh_subscription))
                            }
                            IconButton(onClick = { deletingSubscription = subscription }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = stringResource(R.string.delete_subscription),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                )
                if (expanded) {
                    if (grouped.isEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.no_servers_in_subscription)) },
                            colors = listColors,
                        )
                    } else {
                        grouped.forEach { server ->
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            ServerRow(
                                server = server,
                                selected = server.id == settings.activeServerId,
                                colors = listColors,
                                onSelect = { onSelectServer(server.id) },
                                onInfo = { editing = server },
                                onDelete = { deletingServer = server },
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { server ->
        ServerEditDialog(
            server = server,
            onDismiss = { editing = null },
            onSave = { name, address, port, sni, flow, fingerprint, alpn, note ->
                onUpdateServer(server.id, name, address, port, sni, flow, fingerprint, alpn, note) { result ->
                    if (result.isSuccess) {
                        editing = null
                        onMessage(context.getString(R.string.params_saved))
                    } else {
                        onMessage(result.exceptionOrNull()?.message ?: context.getString(R.string.save_failed))
                    }
                }
            },
        )
    }
    editingSubscription?.let { subscription ->
        SubscriptionNoteDialog(
            subscription = subscription,
            onDismiss = { editingSubscription = null },
            onSave = { note ->
                onUpdateSubscriptionNote(subscription.id, note) { result ->
                    if (result.isSuccess) {
                        editingSubscription = null
                        onMessage(context.getString(R.string.params_saved))
                    } else {
                        onMessage(result.exceptionOrNull()?.message ?: context.getString(R.string.save_failed))
                    }
                }
            },
        )
    }
    deletingServer?.let { server ->
        AlertDialog(
            onDismissRequest = { deletingServer = null },
            title = { Text(stringResource(R.string.delete_server_title)) },
            text = { Text(server.visibleName()) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteServer(server.id)
                    deletingServer = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deletingServer = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    deletingSubscription?.let { subscription ->
        AlertDialog(
            onDismissRequest = { deletingSubscription = null },
            title = { Text(stringResource(R.string.delete_subscription_title)) },
            text = { Text(stringResource(R.string.delete_subscription_body, subscription.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSubscription(subscription.id)
                    deletingSubscription = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deletingSubscription = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun GroupHeader(
    title: String,
    subtitle: String,
    expanded: Boolean,
    colors: ListItemColors,
    onToggle: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
            )
        },
        trailingContent = trailing,
        colors = colors,
        modifier = Modifier.clickable(onClick = onToggle),
    )
}

@Composable
private fun groupSubtitle(servers: List<SavedServer>, activeServerId: String?, expanded: Boolean): String {
    val selected = servers.find { it.id == activeServerId }
    if (!expanded && selected != null) return selected.visibleName()
    return serverCountLabel(servers.size)
}

@Composable
private fun serverCountLabel(count: Int): String =
    if (count == 0) {
        stringResource(R.string.no_servers_count)
    } else {
        pluralStringResource(R.plurals.server_count, count, count)
    }

@Composable
private fun ServerRow(
    server: SavedServer,
    selected: Boolean,
    colors: ListItemColors,
    onSelect: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(server.visibleName(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            RadioButton(selected = selected, onClick = onSelect)
        },
        trailingContent = {
            Row {
                IconButton(onClick = onInfo) {
                    Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.server_params))
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.delete_server),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        colors = colors,
        modifier = Modifier.clickable(onClick = onSelect),
    )
}

@Composable
private fun ServerEditDialog(
    server: SavedServer,
    onDismiss: () -> Unit,
    onSave: (name: String, address: String, port: Int, sni: String, flow: String, fingerprint: String, alpn: String, note: String) -> Unit,
) {
    var note by remember { mutableStateOf(server.note) }
    var name by remember { mutableStateOf(server.name.ifBlank { server.config.visibleName() }) }
    var address by remember { mutableStateOf(server.config.address) }
    var port by remember { mutableStateOf(server.config.port.toString()) }
    var sni by remember { mutableStateOf(server.config.serverName) }
    var flow by remember { mutableStateOf(server.config.flow) }
    var fingerprint by remember { mutableStateOf(server.config.fingerprint) }
    var alpn by remember { mutableStateOf(server.config.alpn) }
    fun save() {
        onSave(
            name,
            address,
            port.toIntOrNull() ?: server.config.port,
            sni,
            flow,
            fingerprint,
            alpn,
            note,
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.server_params)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    note,
                    { note = it },
                    label = { Text(stringResource(R.string.field_note)) },
                    placeholder = { Text(stringResource(R.string.field_note_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.field_name)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(address, { address = it }, label = { Text(stringResource(R.string.field_address)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    port,
                    { port = it },
                    label = { Text(stringResource(R.string.field_port)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(sni, { sni = it }, label = { Text("TLS server name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(flow, { flow = it }, label = { Text("Flow") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(fingerprint, { fingerprint = it }, label = { Text("TLS fingerprint") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(alpn, { alpn = it }, label = { Text("ALPN") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun SubscriptionNoteDialog(
    subscription: SavedSubscription,
    onDismiss: () -> Unit,
    onSave: (note: String) -> Unit,
) {
    var note by remember { mutableStateOf(subscription.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subscription_params)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    subscription.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    note,
                    { note = it },
                    label = { Text(stringResource(R.string.field_note)) },
                    placeholder = { Text(stringResource(R.string.field_note_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave(note) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(note) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
