package com.markedoe.ondo.ui.screens

import com.markedoe.ondo.ui.tv.tvTopBarSpace
import com.markedoe.ondo.ui.tv.rememberTabGridState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.ui.components.ChannelLogo
import com.markedoe.ondo.ui.components.PersonTile
import com.markedoe.ondo.ui.components.PosterCard
import com.markedoe.ondo.ui.components.rememberShelfMetrics
import com.markedoe.ondo.ui.model.ChannelRow
import com.markedoe.ondo.ui.model.PersonRow
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.tv.focusFrame
import com.markedoe.ondo.ui.tv.isTelevision

/** What My List shows: its movies, its series, its channels, its people or its collections. */
enum class MyListKind { MOVIES, SERIES, CHANNELS, PEOPLE, COLLECTIONS }

data class MyListUiState(
    val movies: List<ShowcaseItem> = emptyList(),
    val series: List<ShowcaseItem> = emptyList(),
    val channels: List<ChannelRow> = emptyList(),
    val collections: List<CollectionRow> = emptyList(),
    /** The actors and directors kept, whatever the playlist. */
    val people: List<PersonRow> = emptyList(),
)

class MyListActions(
    val onKind: (MyListKind) -> Unit = {},
    val onOpen: (ShowcaseItem) -> Unit = {},
    val onPlayChannel: (ChannelRow) -> Unit = {},
    val onSearch: () -> Unit = {},
    val onOpenCollection: (String) -> Unit = {},
    /** Opens an actor's or a director's page. */
    val onPerson: (String) -> Unit = {},
    /** Makes an empty collection with this name. */
    val onNewCollection: (String) -> Unit = {},
)

/** The My List tab: the movies, series and channels the viewer keeps, as on the Apple app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyListScreen(state: MyListUiState, kind: MyListKind, actions: MyListActions) {
    val metrics = rememberShelfMetrics()
    var naming by rememberSaveable { mutableStateOf(false) }
    LazyVerticalGrid(
        state = rememberTabGridState(),
        columns = GridCells.Adaptive(if (metrics.compact) 108.dp else 150.dp),
        contentPadding = PaddingValues(start = metrics.gutter, top = tvTopBarSpace(), end = metrics.gutter, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).testTag("myList"),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            TopAppBar(
                title = { Text(stringResource(R.string.my_list), style = MaterialTheme.typography.headlineSmall) },
                // On a TV, Search is in the top bar for every page: no second one here.
                actions = {
                    if (!isTelevision()) {
                        IconButton(onClick = actions.onSearch) { Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.search)) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                windowInsets = WindowInsets(0),
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            val options = listOf(
                MyListKind.MOVIES to R.string.movies,
                MyListKind.SERIES to R.string.series,
                MyListKind.CHANNELS to R.string.channels,
                MyListKind.PEOPLE to R.string.people,
                MyListKind.COLLECTIONS to R.string.collections,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (value, label) ->
                    SegmentedButton(selected = kind == value, onClick = { actions.onKind(value) }, shape = SegmentedButtonDefaults.itemShape(index, options.size)) {
                        Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        when (kind) {
            MyListKind.MOVIES, MyListKind.SERIES -> {
                val items = if (kind == MyListKind.MOVIES) state.movies else state.series
                items(items, key = { it.key }) { item -> PosterCard(item, width = null, onClick = { actions.onOpen(item) }, showsTitle = true) }
            }
            MyListKind.CHANNELS -> items(state.channels, key = { it.channel.id }, span = { GridItemSpan(maxLineSpan) }) { row ->
                Row(
                    Modifier.fillMaxWidth().focusFrame(RoundedCornerShape(10.dp), grow = 1.02f).clip(RoundedCornerShape(10.dp)).clickable { actions.onPlayChannel(row) }.padding(vertical = 6.dp),
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
            MyListKind.PEOPLE -> items(state.people, key = { "person:${it.name}" }) { person -> PersonTile(person, onClick = { actions.onPerson(person.name) }) }
            MyListKind.COLLECTIONS -> collectionRows(state.collections, onOpen = actions.onOpenCollection, onNew = { naming = true })
        }
        val empty = when (kind) {
            MyListKind.MOVIES -> state.movies.isEmpty()
            MyListKind.SERIES -> state.series.isEmpty()
            MyListKind.CHANNELS -> state.channels.isEmpty()
            MyListKind.PEOPLE -> state.people.isEmpty()
            MyListKind.COLLECTIONS -> false
        }
        if (empty) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.my_list_empty), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(
                            when (kind) {
                                MyListKind.CHANNELS -> R.string.no_favorite_channels
                                MyListKind.PEOPLE -> R.string.my_list_people_hint
                                else -> R.string.my_list_empty_hint
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (naming) {
        CollectionNameDialog(
            title = stringResource(R.string.new_collection),
            initial = stringResource(R.string.collection_default_name, state.collections.size + 1),
            confirm = stringResource(R.string.create),
            onConfirm = { naming = false; actions.onNewCollection(it) },
            onDismiss = { naming = false },
        )
    }
}
