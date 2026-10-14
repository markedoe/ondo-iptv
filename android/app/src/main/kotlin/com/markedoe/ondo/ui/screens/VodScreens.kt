package com.markedoe.ondo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.data.PosterSort
import com.markedoe.ondo.ui.components.ContinueCard
import com.markedoe.ondo.ui.components.PosterCard
import com.markedoe.ondo.ui.components.Shelf
import com.markedoe.ondo.ui.components.rememberShelfMetrics
import com.markedoe.ondo.ui.model.ResumeItem
import com.markedoe.ondo.ui.model.ShowcaseItem

data class CategoryRow(val category: MediaCategory, val items: List<ShowcaseItem>)

data class VodUiState(
    val title: String,
    val categories: List<MediaCategory> = emptyList(),
    val lockedCategoryIds: Set<String> = emptySet(),
    val continueWatching: List<ResumeItem> = emptyList(),
    val myList: List<ShowcaseItem> = emptyList(),
    val recent: List<ShowcaseItem> = emptyList(),
    val rows: List<CategoryRow> = emptyList(),
)

class VodActions(
    val onOpen: (ShowcaseItem) -> Unit = {},
    val onResume: (ResumeItem) -> Unit = {},
    val onCategory: (MediaCategory?) -> Unit = {},
    val onSeeAllMyList: () -> Unit = {},
    val onSearch: () -> Unit = {},
)

/** Movies or Series tab: category chips, continue watching, My List, recently added, one row per category. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VodScreen(state: VodUiState, actions: VodActions) {
    val metrics = rememberShelfMetrics()
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("vod"),
        verticalArrangement = Arrangement.spacedBy(if (metrics.compact) 24.dp else 32.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            TopAppBar(
                title = { Text(state.title, style = MaterialTheme.typography.headlineSmall) },
                actions = {
                    IconButton(onClick = actions.onSearch) { Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.search)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = metrics.gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { AssistChip(onClick = { actions.onCategory(null) }, label = { Text(stringResource(R.string.all)) }) }
                items(state.categories, key = { it.id }) { category ->
                    val locked = category.id in state.lockedCategoryIds
                    AssistChip(
                        onClick = { actions.onCategory(category) },
                        label = { Text(category.name, maxLines = 1) },
                        leadingIcon = if (locked) ({ Icon(Icons.Rounded.Lock, contentDescription = null) }) else null,
                    )
                }
            }
        }
        if (state.continueWatching.isNotEmpty()) {
            item {
                Shelf(stringResource(R.string.continue_watching), metrics) {
                    items(state.continueWatching, key = { it.key }) { ContinueCard(it, metrics.wideCardWidth, onClick = { actions.onResume(it) }) }
                }
            }
        }
        if (state.myList.isNotEmpty()) {
            item {
                Shelf(stringResource(R.string.my_list), metrics, onSeeAll = actions.onSeeAllMyList) {
                    items(state.myList, key = { it.key }) { PosterCard(it, metrics.posterWidth, onClick = { actions.onOpen(it) }) }
                }
            }
        }
        if (state.recent.isNotEmpty()) {
            item {
                Shelf(stringResource(R.string.recently_added), metrics) {
                    items(state.recent, key = { it.key }) { PosterCard(it, metrics.posterWidth, onClick = { actions.onOpen(it) }) }
                }
            }
        }
        items(state.rows, key = { it.category.id }) { row ->
            Shelf(row.category.name, metrics, onSeeAll = { actions.onCategory(row.category) }) {
                items(row.items, key = { it.key }) { PosterCard(it, metrics.posterWidth, onClick = { actions.onOpen(it) }) }
            }
        }
        if (state.rows.isEmpty() && state.recent.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.nothing_here),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = metrics.gutter),
                )
            }
        }
    }
}

/** Poster grid of a category (or of everything), with a filter and a sort menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosterGridScreen(
    title: String,
    items: List<ShowcaseItem>,
    filter: String,
    onFilter: (String) -> Unit,
    onOpen: (ShowcaseItem) -> Unit,
    onBack: () -> Unit,
    sort: PosterSort = PosterSort.PLAYLIST,
    onSort: (PosterSort) -> Unit = {},
) {
    var sorting by remember { mutableStateOf(false) }
    val metrics = rememberShelfMetrics()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (metrics.compact) 108.dp else 150.dp),
        contentPadding = PaddingValues(start = metrics.gutter, end = metrics.gutter, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).testTag("grid"),
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = { sorting = true }, modifier = Modifier.testTag("sort")) {
                        Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = stringResource(R.string.sort))
                    }
                    DropdownMenu(expanded = sorting, onDismissRequest = { sorting = false }) {
                        PosterSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(stringResource(option.label)) },
                                leadingIcon = { if (option == sort) Icon(Icons.Rounded.Check, contentDescription = null) },
                                onClick = {
                                    sorting = false
                                    onSort(option)
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                windowInsets = WindowInsets(0),
            )
        }
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            OutlinedTextField(
                value = filter,
                onValueChange = onFilter,
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.filter)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        items(items, key = { it.key }) { item -> PosterCard(item, width = null, onClick = { onOpen(item) }, showsTitle = true) }
        if (items.isEmpty()) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.nothing_here), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val PosterSort.label: Int
    get() = when (this) {
        PosterSort.PLAYLIST -> R.string.sort_playlist_order
        PosterSort.NAME -> R.string.name
        PosterSort.RECENT -> R.string.recently_added
        PosterSort.RELEASE -> R.string.sort_release_date
        PosterSort.RATING -> R.string.sort_rating
    }
