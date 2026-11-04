package com.markedoe.ondo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.markedoe.ondo.R
import com.markedoe.ondo.core.library.RowOrder
import com.markedoe.ondo.ui.theme.ondoSwitchColors
import kotlin.math.abs

/** A row of the Home, Movies or Series tab in Settings → Row order: its key ([RowOrder]), the category's name (null: a built-in row), whether it is hidden. */
data class RowOrderEntry(val key: String, val category: String?, val hidden: Boolean)

/** The rows of the Home, Movies and Series tabs, in the viewer's order. */
data class RowOrderUiState(
    val movies: List<RowOrderEntry> = emptyList(),
    val series: List<RowOrderEntry> = emptyList(),
    val home: List<RowOrderEntry> = emptyList(),
) {
    /** The rows of a [tab] as sync names it ([RowOrder.HOME], [RowOrder.MOVIES], [RowOrder.SERIES]). */
    fun rows(tab: String): List<RowOrderEntry> = when (tab) {
        RowOrder.HOME -> home
        RowOrder.SERIES -> series
        else -> movies
    }

    /** This state with the rows of [tab] as [rows]. */
    fun with(tab: String, rows: List<RowOrderEntry>): RowOrderUiState = when (tab) {
        RowOrder.HOME -> copy(home = rows)
        RowOrder.SERIES -> copy(series = rows)
        else -> copy(movies = rows)
    }

    companion object {
        /** The tabs of Settings → Row order, in the order of the app's tabs. */
        val TABS = listOf(RowOrder.HOME, RowOrder.MOVIES, RowOrder.SERIES)

        /** A tab's name in test tags: `home`, `movies`, `series`. */
        fun tag(tab: String): String = when (tab) {
            RowOrder.HOME -> "home"
            RowOrder.SERIES -> "series"
            else -> "movies"
        }
    }
}

class RowOrderActions(
    val onBack: () -> Unit = {},
    /** The rows of a tab (as sync names it: [RowOrder.HOME], [RowOrder.MOVIES], [RowOrder.SERIES]) in their new order, and those hidden: saved at once. */
    val onSave: (tab: String, arranged: List<String>, hidden: List<String>) -> Unit = { _, _, _ -> },
    /** Default order: the tab's rows in their default order again. */
    val onReset: (tab: String) -> Unit = {},
)

/** The name of a tab in Settings → Row order. */
@Composable
fun tabName(tab: String): String = stringResource(
    when (tab) {
        RowOrder.HOME -> R.string.home
        RowOrder.SERIES -> R.string.series
        else -> R.string.movies
    },
)

/** The name of a row of the tab: a built-in row's, else its category's. */
@Composable
fun rowName(entry: RowOrderEntry, tab: String): String = entry.category ?: stringResource(
    if (tab == RowOrder.HOME) {
        when (entry.key) {
            RowOrder.HOME_CONTINUE_MOVIES -> R.string.movies_in_progress
            RowOrder.HOME_CONTINUE_SERIES -> R.string.series_in_progress
            RowOrder.MY_LIST -> R.string.my_list
            RowOrder.HOME_NEW_MOVIES -> R.string.new_movies
            RowOrder.HOME_NEW_SERIES -> R.string.new_series
            RowOrder.TRENDING -> R.string.trending
            RowOrder.HOME_CHANNELS -> R.string.live_tv
            RowOrder.HOME_COLLECTIONS -> R.string.collections
            else -> R.string.home
        }
    } else {
        val series = tab == RowOrder.SERIES
        when (entry.key) {
            RowOrder.ALL -> if (series) R.string.all_series else R.string.all_movies
            RowOrder.CONTINUE_WATCHING -> R.string.continue_watching
            RowOrder.MY_LIST -> R.string.my_list
            RowOrder.NEW_RELEASES -> if (series) R.string.new_series else R.string.new_movies
            RowOrder.TRENDING -> R.string.trending
            RowOrder.AWARD_WINNERS -> R.string.award_winners
            else -> R.string.categories
        }
    },
)

/** [this] with the row at [from] moved to [to]. */
fun <T> List<T>.moving(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/** Saves [rows] of a tab with [actions]: their keys in order, and those hidden. */
internal fun RowOrderActions.save(tab: String, rows: List<RowOrderEntry>) =
    onSave(tab, rows.map { it.key }, rows.filter { it.hidden }.map { it.key })

/**
 * Settings → Row order, on a phone or a tablet: the rows of Home, Movies, then Series, each with a handle to drag it
 * (long press anywhere on the row, or the handle at once) and a switch to show or hide it; Default order puts the tab's
 * rows back as they come. TalkBack moves a row with its Move up and Move down actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowOrderScreen(state: RowOrderUiState, actions: RowOrderActions) {
    // The order being dragged, before it is saved; the saved one otherwise.
    var dragged by remember { mutableStateOf<Pair<String, List<RowOrderEntry>>?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(state) { dragged = null }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing).testTag("rowOrder")) {
        TopAppBar(
            title = { Text(stringResource(R.string.row_order)) },
            navigationIcon = {
                IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) }
            },
            windowInsets = WindowInsets(0),
        )
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.testTag("rowOrderList")) {
            item(key = "hint") {
                Text(
                    stringResource(R.string.row_order_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            for (syncTab in RowOrderUiState.TABS) {
                val rows = dragged?.takeIf { it.first == syncTab }?.second ?: state.rows(syncTab)
                tab(syncTab, rows, list, actions, onDrag = { dragged = it?.let { syncTab to it } })
            }
        }
    }
}

private fun LazyListScope.tab(
    syncTab: String,
    rows: List<RowOrderEntry>,
    list: LazyListState,
    actions: RowOrderActions,
    onDrag: (List<RowOrderEntry>?) -> Unit,
) {
    val tab = RowOrderUiState.tag(syncTab)
    item(key = "title-$tab") {
        ListItem(
            headlineContent = {
                Text(tabName(syncTab), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            },
            trailingContent = {
                TextButton(onClick = { actions.onReset(syncTab) }, modifier = Modifier.testTag("rowOrderDefault-$tab")) { Text(stringResource(R.string.row_order_default)) }
            },
        )
    }
    items(rows, key = { "$tab|${it.key}" }) { entry ->
        val name = rowName(entry, syncTab)
        val index = rows.indexOf(entry)
        val latest by rememberUpdatedState(rows)
        var offset by remember { mutableFloatStateOf(0f) }
        var dragging by remember { mutableStateOf(false) }
        // Dragged: the row follows the finger; past half of the next one, they swap places. Saved when let go.
        var current by remember { mutableStateOf(rows) }
        var original by remember { mutableStateOf(rows) }
        val start = { original = latest; current = latest; dragging = true; offset = 0f }
        // Saved, the order dragged stays on screen until the saved one comes back.
        val end = { done: Boolean ->
            dragging = false
            offset = 0f
            if (done && current != original) actions.save(syncTab, current) else onDrag(null)
        }
        val step = { delta: Float ->
            offset += delta
            val at = current.indexOfFirst { it.key == entry.key }
            val target = if (offset > 0 && at < current.lastIndex) at + 1 else if (offset < 0 && at > 0) at - 1 else -1
            val other = current.getOrNull(target)?.let { next -> list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "$tab|${next.key}" } }
            if (other != null && abs(offset) > other.size / 2f) {
                current = current.moving(at, target)
                offset += if (target > at) -other.size.toFloat() else other.size.toFloat()
                onDrag(current)
            }
        }
        val handle = Modifier.pointerInput(entry.key) {
            detectVerticalDragGestures(
                onDragStart = { start() },
                onDragEnd = { end(true) },
                onDragCancel = { end(false) },
                onVerticalDrag = { change, delta -> change.consume(); step(delta) },
            )
        }
        val longPress = Modifier.pointerInput(entry.key) {
            detectDragGesturesAfterLongPress(
                onDragStart = { start() },
                onDragEnd = { end(true) },
                onDragCancel = { end(false) },
                onDrag = { change, amount -> change.consume(); step(amount.y) },
            )
        }
        val moveUp = stringResource(R.string.move_up)
        val moveDown = stringResource(R.string.move_down)
        ListItem(
            headlineContent = { Text(name, maxLines = 1) },
            leadingContent = {
                Icon(Icons.Rounded.DragHandle, contentDescription = null, modifier = handle.testTag("rowHandle-$tab-${entry.key}"))
            },
            trailingContent = {
                Switch(
                    checked = !entry.hidden,
                    onCheckedChange = { shown -> actions.save(syncTab, rows.map { if (it.key == entry.key) it.copy(hidden = !shown) else it }) },
                    colors = ondoSwitchColors(),
                    modifier = Modifier.testTag("rowSwitch-$tab-${entry.key}").semantics { contentDescription = name },
                )
            },
            colors = if (dragging) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest) else ListItemDefaults.colors(),
            tonalElevation = if (dragging) 6.dp else 0.dp,
            shadowElevation = if (dragging) 6.dp else 0.dp,
            modifier = Modifier
                .zIndex(if (dragging) 1f else 0f)
                .graphicsLayer { translationY = if (dragging) offset else 0f }
                .then(longPress)
                .fillMaxWidth()
                .semantics {
                    customActions = listOfNotNull(
                        CustomAccessibilityAction(moveUp) { actions.save(syncTab, rows.moving(index, index - 1)); true }.takeIf { index > 0 },
                        CustomAccessibilityAction(moveDown) { actions.save(syncTab, rows.moving(index, index + 1)); true }.takeIf { index < rows.lastIndex },
                    )
                }
                .testTag("row-$tab-${entry.key}"),
        )
    }
}
