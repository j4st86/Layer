package com.layer.app.ui.apps

import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.layer.app.R
import com.layer.app.data.InstalledApp
import com.layer.app.ui.LocalAppContainer
import com.layer.app.ui.components.AppModeSelector
import com.layer.app.ui.components.EmptyState
import com.layer.app.ui.components.SearchField
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
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    var pendingApp by remember { mutableStateOf<InstalledApp?>(null) }
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
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val sheetColor = MaterialTheme.colorScheme.surfaceContainerLow
        val sheetHeight = LocalConfiguration.current.screenHeightDp.dp * 0.94f
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = sheetState,
            containerColor = sheetColor,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(sheetHeight)
                    .imePadding()
                    .padding(horizontal = 16.dp),
            ) {
                Text(stringResource(R.string.apps_add), style = MaterialTheme.typography.headlineSmall)
                SearchField(
                    pickerQuery,
                    viewModel::onPickerQuery,
                    stringResource(R.string.apps_search),
                    Modifier.padding(vertical = 12.dp),
                )
                val pickerListState = rememberLazyListState()
                val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .nestedScroll(ConsumeSheetOverflow),
                ) {
                    CompositionLocalProvider(LocalOverscrollFactory provides PassThroughOverscrollFactory) {
                        LazyColumn(
                            state = pickerListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .lazyListScrollbar(pickerListState, scrollbarColor),
                        ) {
                            items(pickerApps, key = { it.packageName }) { app ->
                                ListItem(
                                    headlineContent = { Text(app.label) },
                                    supportingContent = { Text(app.packageName) },
                                    leadingContent = { AppIcon(app.icon) },
                                    colors = ListItemDefaults.colors(containerColor = sheetColor),
                                    modifier = Modifier.clickable {
                                        pendingApp = app
                                        showPicker = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
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

private val ConsumeSheetOverflow = object : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset = Offset(x = 0f, y = available.y)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(x = 0f, y = available.y)
}

private object PassThroughOverscrollFactory : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect = PassThroughOverscroll
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = 0
}

private object PassThroughOverscroll : OverscrollEffect {
    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset = performScroll(delta)

    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity,
    ) {
        performFling(velocity)
    }

    override val isInProgress: Boolean = false
}

@Composable
private fun SpacerBottom() {
    androidx.compose.foundation.layout.Spacer(Modifier.size(88.dp))
}

private fun Modifier.lazyListScrollbar(
    state: LazyListState,
    color: Color,
    width: Dp = 3.dp,
): Modifier = drawWithContent {
    drawContent()
    val info = state.layoutInfo
    val visible = info.visibleItemsInfo
    val total = info.totalItemsCount
    if (visible.isEmpty() || total == 0) return@drawWithContent

    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    if (viewport <= 0f) return@drawWithContent

    val averageItemSize = visible.sumOf { it.size }.toFloat() / visible.size
    val estimatedTotal = averageItemSize * total +
        info.beforeContentPadding + info.afterContentPadding
    if (estimatedTotal <= viewport) return@drawWithContent

    val first = visible.first()
    val scrolled = first.index * averageItemSize - first.offset + info.beforeContentPadding
    val maxScroll = (estimatedTotal - viewport).coerceAtLeast(1f)
    val fraction = (scrolled / maxScroll).coerceIn(0f, 1f)

    val barWidth = width.toPx()
    val inset = 2.dp.toPx()
    val minThumb = 24.dp.toPx()
    val thumbHeight = (viewport * viewport / estimatedTotal).coerceIn(minThumb, viewport)
    val thumbTop = fraction * (viewport - thumbHeight)
    val x = size.width - barWidth - inset

    drawRoundRect(
        color = color,
        topLeft = Offset(x, 0f),
        size = Size(barWidth, viewport),
        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
        alpha = 0.18f,
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(x, thumbTop),
        size = Size(barWidth, thumbHeight),
        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
        alpha = 0.55f,
    )
}

@Composable
fun AppIcon(drawable: Drawable?) {
    if (drawable == null) return
    val bitmap = remember(drawable) { drawable.toBitmap().asImageBitmap() }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = Modifier
            .size(40.dp)
            .clip(MaterialTheme.shapes.small),
    )
}
