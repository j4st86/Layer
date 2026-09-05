package com.layer.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.layer.app.R
import com.layer.core.model.AppRoutingMode
import com.layer.core.model.DomainRoutingMode

@Composable
fun AppRoutingMode.label(): String = when (this) {
    AppRoutingMode.SMART -> stringResource(R.string.mode_smart)
    AppRoutingMode.VPN -> stringResource(R.string.mode_vpn)
    AppRoutingMode.DIRECT -> stringResource(R.string.mode_direct)
}

private val appRuleModes = listOf(AppRoutingMode.VPN, AppRoutingMode.DIRECT)

@Composable
fun DomainRoutingMode.label(): String = when (this) {
    DomainRoutingMode.VPN -> stringResource(R.string.mode_vpn)
    DomainRoutingMode.DIRECT -> stringResource(R.string.mode_direct)
}

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        placeholder = { Text(placeholder) },
        shape = MaterialTheme.shapes.extraLarge,
    )
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun AppModeSelector(
    selected: AppRoutingMode?,
    modifier: Modifier = Modifier,
    onSelected: (AppRoutingMode) -> Unit,
) {
    val visible = when (selected) {
        null -> null
        AppRoutingMode.SMART -> AppRoutingMode.VPN
        else -> selected
    }
    ModeChipRow(
        options = appRuleModes.map { it.label() },
        selectedIndex = visible?.let { appRuleModes.indexOf(it) } ?: -1,
        modifier = modifier,
        onSelected = { onSelected(appRuleModes[it]) },
    )
}

@Composable
fun DomainModeSelector(
    selected: DomainRoutingMode,
    modifier: Modifier = Modifier,
    onSelected: (DomainRoutingMode) -> Unit,
) {
    ModeChipRow(
        options = DomainRoutingMode.entries.map { it.label() },
        selectedIndex = DomainRoutingMode.entries.indexOf(selected),
        modifier = modifier,
        onSelected = { onSelected(DomainRoutingMode.entries[it]) },
    )
}

@Composable
private fun ModeChipRow(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelected: (Int) -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Surface(
                onClick = { onSelected(index) },
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Text(
                    text = label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .wrapContentHeight(Alignment.CenterVertically)
                        .padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
fun TonalCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        content = content,
    )
}
