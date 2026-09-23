package com.layer.app.ui.apps

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.layer.app.data.InstalledApp
import com.layer.app.ui.components.SearchField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerSheet(
    title: String,
    query: String,
    onQueryChange: (String) -> Unit,
    searchPlaceholder: String,
    apps: List<InstalledApp>,
    onDismiss: () -> Unit,
    onPick: (InstalledApp) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetColor = MaterialTheme.colorScheme.surfaceContainerLow
    val sheetHeight = LocalConfiguration.current.screenHeightDp.dp * 0.94f
    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            Text(title, style = MaterialTheme.typography.headlineSmall)
            SearchField(
                query,
                onQueryChange,
                searchPlaceholder,
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
                        items(apps, key = { it.packageName }) { app ->
                            ListItem(
                                headlineContent = { Text(app.label) },
                                supportingContent = { Text(app.packageName) },
                                leadingContent = { AppIcon(app.icon) },
                                colors = ListItemDefaults.colors(containerColor = sheetColor),
                                modifier = Modifier.clickable { onPick(app) },
                            )
                        }
                    }
                }
            }
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
