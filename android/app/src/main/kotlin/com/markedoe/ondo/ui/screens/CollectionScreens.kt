package com.markedoe.ondo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.ui.components.ChannelLogo
import com.markedoe.ondo.ui.components.PosterCard
import com.markedoe.ondo.ui.components.rememberShelfMetrics
import com.markedoe.ondo.ui.model.ChannelRow
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.tv.focusFrame
import com.markedoe.ondo.ui.tv.rememberTvTextField

/** A collection in My List: its name and how many items of the current playlist it holds. */
data class CollectionRow(val id: String, val name: String, val count: Int)

/** A collection offered on a movie or series page, ticked when it holds the item. */
data class CollectionChoice(val id: String, val name: String, val contains: Boolean)

data class CollectionUiState(
    val id: String,
    val name: String,
    val movies: List<ShowcaseItem> = emptyList(),
    val series: List<ShowcaseItem> = emptyList(),
    val channels: List<ChannelRow> = emptyList(),
) {
    val isEmpty: Boolean get() = movies.isEmpty() && series.isEmpty() && channels.isEmpty()
}

class CollectionActions(
    val onBack: () -> Unit = {},
    val onOpen: (ShowcaseItem) -> Unit = {},
    val onPlayChannel: (ChannelRow) -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onDelete: () -> Unit = {},
)

/** One collection: its movies, series and channels, with Rename and Delete in the menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(state: CollectionUiState, actions: CollectionActions) {
    val metrics = rememberShelfMetrics()
    var menu by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (metrics.compact) 108.dp else 150.dp),
        contentPadding = PaddingValues(start = metrics.gutter, end = metrics.gutter, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).testTag("collection"),
    ) {
        fullWidth {
            TopAppBar(
                title = { Text(state.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("collectionMenu")) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.rename_collection)) }, onClick = { menu = false; renaming = true })
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete_collection), color = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; deleting = true },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                windowInsets = WindowInsets(0),
            )
        }
        posters(R.string.movies, state.movies, actions.onOpen)
        posters(R.string.series, state.series, actions.onOpen)
        if (state.channels.isNotEmpty()) {
            fullWidth { Text(stringResource(R.string.channels), style = MaterialTheme.typography.titleLarge) }
            items(state.channels, key = { it.channel.id }, span = { GridItemSpan(maxLineSpan) }) { row -> ChannelLine(row, actions.onPlayChannel) }
        }
        if (state.isEmpty) {
            fullWidth {
                Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.empty_collection), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.empty_collection_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (renaming) {
        CollectionNameDialog(
            title = stringResource(R.string.rename_collection),
            initial = state.name,
            confirm = stringResource(R.string.rename),
            onConfirm = { renaming = false; actions.onRename(it) },
            onDismiss = { renaming = false },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.delete_collection_confirm)) },
            text = { Text(state.name) },
            confirmButton = {
                TextButton(onClick = { deleting = false; actions.onDelete() }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** The Collections part of My List: a button to make one, then each collection. */
fun LazyGridScope.collectionRows(rows: List<CollectionRow>, onOpen: (String) -> Unit, onNew: () -> Unit) {
    fullWidth {
        ListItem(
            headlineContent = { Text(stringResource(R.string.new_collection), color = MaterialTheme.colorScheme.primary) },
            leadingContent = { Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.focusFrame(RoundedCornerShape(10.dp), grow = 1.02f).clip(RoundedCornerShape(10.dp)).clickable(onClick = onNew).testTag("newCollection"),
        )
    }
    items(rows, key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { row ->
        ListItem(
            headlineContent = { Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingContent = { Icon(Icons.Rounded.CollectionsBookmark, contentDescription = null) },
            trailingContent = { Text(row.count.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant) },
            modifier = Modifier.focusFrame(RoundedCornerShape(10.dp), grow = 1.02f).clip(RoundedCornerShape(10.dp)).clickable { onOpen(row.id) }.testTag("collection-${row.name}"),
        )
    }
    if (rows.isEmpty()) {
        fullWidth {
            Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.no_collections), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.no_collections_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** On a movie or series page: tick the collections that hold it, or make a new one with it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionPicker(choices: List<CollectionChoice>, onToggle: (String) -> Unit, onNew: (String) -> Unit, onDismiss: () -> Unit) {
    var naming by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp).testTag("collectionPicker")) {
            Text(
                stringResource(R.string.add_to_collection),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            choices.forEach { choice ->
                ListItem(
                    headlineContent = { Text(choice.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { Checkbox(checked = choice.contains, onCheckedChange = null) },
                    modifier = Modifier
                        .focusFrame(RoundedCornerShape(12.dp), grow = 1f).toggleable(value = choice.contains, role = Role.Checkbox, onValueChange = { onToggle(choice.id) })
                        .testTag("pick-${choice.name}"),
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.new_collection), color = MaterialTheme.colorScheme.primary) },
                leadingContent = { Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.focusFrame(RoundedCornerShape(12.dp), grow = 1f).clickable { naming = true }.testTag("pickNewCollection"),
            )
        }
    }
    if (naming) {
        CollectionNameDialog(
            title = stringResource(R.string.new_collection),
            initial = stringResource(R.string.collection_default_name, choices.size + 1),
            confirm = stringResource(R.string.create),
            onConfirm = { naming = false; onNew(it) },
            onDismiss = { naming = false },
        )
    }
}

/** Asks for a collection's name. */
@Composable
fun CollectionNameDialog(title: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            val field = rememberTvTextField()
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.name)) },
                // The keyboard's Done (the TV's too) makes the collection.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onConfirm(name.trim()) }),
                readOnly = field.readOnly,
                modifier = Modifier.then(field.modifier).testTag("collectionName"),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun LazyGridScope.fullWidth(content: @Composable () -> Unit) = item(span = { GridItemSpan(maxLineSpan) }) { content() }

private fun LazyGridScope.posters(title: Int, posters: List<ShowcaseItem>, onOpen: (ShowcaseItem) -> Unit) {
    if (posters.isEmpty()) return
    fullWidth { Text(stringResource(title), style = MaterialTheme.typography.titleLarge) }
    items(posters, key = { it.key }) { item -> PosterCard(item, width = null, onClick = { onOpen(item) }, showsTitle = true) }
}

@Composable
private fun ChannelLine(row: ChannelRow, onPlay: (ChannelRow) -> Unit) {
    Row(
        Modifier.fillMaxWidth().focusFrame(RoundedCornerShape(10.dp), grow = 1.02f).clip(RoundedCornerShape(10.dp)).clickable { onPlay(row) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(width = 72.dp, height = 48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(6.dp),
            contentAlignment = Alignment.Center,
        ) { ChannelLogo(url = row.channel.logoUrl, name = row.channel.name) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(row.channel.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            row.nowTitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}
