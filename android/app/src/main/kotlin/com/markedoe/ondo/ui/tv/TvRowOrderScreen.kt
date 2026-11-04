package com.markedoe.ondo.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.markedoe.ondo.R
import com.markedoe.ondo.ui.screens.RowOrderActions
import com.markedoe.ondo.ui.screens.RowOrderEntry
import com.markedoe.ondo.ui.screens.RowOrderUiState
import com.markedoe.ondo.ui.screens.moving
import com.markedoe.ondo.ui.screens.rowName
import com.markedoe.ondo.ui.screens.tabName

/** A row being moved with the remote: which tab (as sync names it), its key, and the tab's rows as they were when it was picked up. */
private data class Moving(val tab: String, val key: String, val before: List<RowOrderEntry>)

/**
 * Settings → Row order on a TV: the rows of Home, Movies, then Series. OK on a row picks it up: it turns to the accent
 * colour with arrows, Up and Down move it, OK puts it down (saved), Back puts it back where it was. Right reaches its
 * switch, which shows or hides it. Default order, at the top of each tab, puts its rows back as they come.
 */
@Composable
fun TvRowOrderScreen(state: RowOrderUiState, actions: RowOrderActions) {
    var moving by remember { mutableStateOf<Moving?>(null) }
    // The tab being moved in, as it is on screen; the saved rows otherwise.
    var arranged by remember { mutableStateOf<List<RowOrderEntry>?>(null) }
    fun rowsOf(tab: String) = arranged?.takeIf { moving?.tab == tab } ?: state.rows(tab)
    // Back while a row is picked up: it goes back where it was, and the page stays.
    BackHandler(enabled = moving != null) {
        moving = null
        arranged = null
    }
    // Once the order saved comes back, it is the one on screen.
    LaunchedEffect(state) { if (moving == null) arranged = null }
    val list = rememberLazyListState()
    FocusEntry(Modifier.fillMaxSize().background(TvTokens.Ink)) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().testTag("rowOrder"),
            contentPadding = PaddingValues(start = TvTokens.EdgeEnd, top = TvTokens.EdgeTop + 12.dp, end = TvTokens.EdgeEnd, bottom = TvTokens.EdgeTop + 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "title") {
                Text(
                    stringResource(R.string.row_order),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                )
            }
            item(key = "hint") {
                Text(
                    stringResource(if (moving != null) R.string.row_order_moving_tv else R.string.row_order_hint_tv),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (moving != null) TvTokens.Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag("rowOrderHint"),
                )
            }
            for (syncTab in RowOrderUiState.TABS) {
                val tab = RowOrderUiState.tag(syncTab)
                item(key = "title-$tab") {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tabName(syncTab),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TvTokens.Accent,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { actions.onReset(syncTab) },
                            enabled = moving == null,
                            modifier = Modifier.testTag("rowOrderDefault-$tab"),
                            scale = ButtonDefaults.scale(focusedScale = 1.06f),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                        ) { Text(stringResource(R.string.row_order_default)) }
                    }
                }
                val rows = rowsOf(syncTab)
                itemsIndexed(rows, key = { _, it -> "$tab|${it.key}" }) { index, entry ->
                    val picked = moving?.let { it.tab == syncTab && it.key == entry.key } == true
                    TvOrderRow(
                        name = rowName(entry, syncTab),
                        entry = entry,
                        index = index,
                        picked = picked,
                        // While a row is picked up, the others stay where they are: the remote moves only it.
                        enabled = moving == null || picked,
                        tag = "$tab-${entry.key}",
                        onOk = {
                            val current = moving
                            if (current == null) {
                                moving = Moving(syncTab, entry.key, rows)
                                arranged = rows
                            } else {
                                // Put down: saved when it moved, left as it was otherwise.
                                val now = arranged ?: rows
                                moving = null
                                if (now != current.before) actions.onSave(syncTab, now.map { it.key }, now.filter { it.hidden }.map { it.key }) else arranged = null
                            }
                        },
                        onMove = { step ->
                            val now = arranged ?: rows
                            val at = now.indexOfFirst { it.key == entry.key }
                            arranged = now.moving(at, (at + step).coerceIn(0, now.lastIndex))
                        },
                        onToggle = {
                            actions.onSave(syncTab, rows.map { it.key }, rows.filter { if (it.key == entry.key) !it.hidden else it.hidden }.map { it.key })
                        },
                    )
                }
            }
        }
    }
}

/**
 * One row of the order: its name (OK picks it up or puts it down; picked up, Up and Down move it and nothing else
 * takes the focus), then its switch.
 */
@Composable
private fun TvOrderRow(
    name: String,
    entry: RowOrderEntry,
    index: Int,
    picked: Boolean,
    enabled: Boolean,
    tag: String,
    onOk: () -> Unit,
    onMove: (Int) -> Unit,
    onToggle: () -> Unit,
) {
    val bringIntoView = remember { BringIntoViewRequester() }
    // Moved, it stays in view.
    LaunchedEffect(picked, index) { if (picked) runCatching { bringIntoView.bringIntoView() } }
    val shape = RoundedCornerShape(14.dp)
    val moving = stringResource(R.string.row_order_moving)
    Row(Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView), verticalAlignment = Alignment.CenterVertically) {
        ListItem(
            selected = picked,
            onClick = onOk,
            enabled = enabled,
            headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingContent = { Icon(if (picked) Icons.Rounded.UnfoldMore else Icons.Rounded.DragHandle, contentDescription = null, modifier = Modifier.size(24.dp)) },
            // Picked up: says so, beside the arrows, while the other rows grey out.
            trailingContent = if (picked) ({ Text(moving, fontWeight = FontWeight.SemiBold) }) else null,
            modifier = Modifier
                .weight(1f)
                .then(if (picked) Modifier.border(3.dp, TvTokens.Accent, shape) else Modifier)
                .onPreviewKeyEvent { event ->
                    if (!picked) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp, Key.DirectionDown -> {
                            if (event.type == KeyEventType.KeyDown) onMove(if (event.key == Key.DirectionUp) -1 else 1)
                            true
                        }
                        // Picked up, the row keeps the focus: the switch and the rest wait until it is put down.
                        Key.DirectionLeft, Key.DirectionRight -> true
                        else -> false
                    }
                }
                .semantics {
                    selected = picked
                    if (picked) stateDescription = moving
                }
                .testTag("row-$tag"),
            colors = if (picked) {
                ListItemDefaults.colors(
                    containerColor = TvTokens.Accent,
                    contentColor = TvTokens.Ink,
                    focusedContainerColor = TvTokens.Accent,
                    focusedContentColor = TvTokens.Ink,
                    selectedContainerColor = TvTokens.Accent,
                    selectedContentColor = TvTokens.Ink,
                    focusedSelectedContainerColor = TvTokens.Accent,
                    focusedSelectedContentColor = TvTokens.Ink,
                    pressedSelectedContainerColor = TvTokens.Accent,
                    pressedSelectedContentColor = TvTokens.Ink,
                )
            } else {
                tvRowColors()
            },
            shape = ListItemDefaults.shape(shape),
            scale = ListItemDefaults.scale(focusedScale = if (picked) 1.04f else 1.02f),
        )
        TvSwitchRow(
            title = stringResource(if (entry.hidden) R.string.row_hidden else R.string.row_shown),
            subtitle = null,
            checked = !entry.hidden,
            onChange = { onToggle() },
            enabled = !picked,
            modifier = Modifier.padding(start = 12.dp).width(220.dp).testTag("rowSwitch-$tag"),
        )
    }
}
