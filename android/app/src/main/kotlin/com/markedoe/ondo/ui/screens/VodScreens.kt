package com.markedoe.ondo.ui.screens

import com.markedoe.ondo.ui.tv.tvTopBarSpace
import com.markedoe.ondo.ui.tv.rememberTabListState
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.foundation.layout.size
import com.markedoe.ondo.ui.tv.isTelevision
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
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
import com.markedoe.ondo.ui.tv.TvRow
import com.markedoe.ondo.ui.tv.focusMemory
import com.markedoe.ondo.ui.tv.TvSeeAllCard
import com.markedoe.ondo.ui.tv.rememberLeaveField
import com.markedoe.ondo.ui.tv.rememberTvTextField

data class CategoryRow(val category: MediaCategory, val items: List<ShowcaseItem>)

data class VodUiState(
    val title: String,
    val categories: List<MediaCategory> = emptyList(),
    val lockedCategoryIds: Set<String> = emptySet(),
    val continueWatching: List<ResumeItem> = emptyList(),
    val myList: List<ShowcaseItem> = emptyList(),
    val rows: List<CategoryRow> = emptyList(),
    /** Movies that won an award (Palme d'Or, Oscars…), the most recent first. */
    val awardWinners: List<ShowcaseItem> = emptyList(),
)

class VodActions(
    val onOpen: (ShowcaseItem) -> Unit = {},
    val onResume: (ResumeItem) -> Unit = {},
    val onRemoveResume: (ResumeItem) -> Unit = {},
    /** Long press on a "Continue watching" card, Go to: the movie's page, or the series' of the episode. */
    val onOpenResume: (ResumeItem) -> Unit = {},
    val onCategory: (MediaCategory?) -> Unit = {},
    val onSeeAllMyList: () -> Unit = {},
    val onSearch: () -> Unit = {},
    val onCategories: () -> Unit = {},
)

/** How many titles a row of the Movies or Series tab shows on a TV before its See all card. */
const val TV_ROW_LENGTH = 10

/** Movies or Series tab: category chips, continue watching, My List, award winners, one row per category. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VodScreen(state: VodUiState, actions: VodActions) {
    val metrics = rememberShelfMetrics()
    val television = isTelevision()
    // On a TV a row shows its first titles, then a See all card: the remote reaches it by going on along the row.
    val rowLength = if (television) TV_ROW_LENGTH else Int.MAX_VALUE
    LazyColumn(
        state = rememberTabListState(),
        modifier = Modifier.fillMaxSize().testTag("vod"),
        verticalArrangement = Arrangement.spacedBy(if (metrics.compact) 24.dp else 32.dp),
        contentPadding = PaddingValues(top = tvTopBarSpace(), bottom = 32.dp),
    ) {
        // On a TV the top bar names the tab and holds Search: the page starts with its categories, the button that
        // chooses which ones show first.
        if (!television) {
            item {
                TopAppBar(
                    title = { Text(state.title, style = MaterialTheme.typography.headlineSmall) },
                    actions = {
                        IconButton(onClick = actions.onCategories, modifier = Modifier.testTag("categoryFilter")) {
                            Icon(Icons.Rounded.FilterList, contentDescription = stringResource(R.string.categories))
                        }
                        IconButton(onClick = actions.onSearch) { Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.search)) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        }
        item {
            TvRow {
                LazyRow(contentPadding = PaddingValues(horizontal = metrics.gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (television) {
                        item {
                            CategoryPill(
                                stringResource(R.string.categories),
                                onClick = actions.onCategories,
                                leadingIcon = { Icon(Icons.Rounded.FilterList, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                modifier = Modifier.testTag("categoryFilter"),
                            )
                        }
                    }
                    item { CategoryPill(stringResource(R.string.all), onClick = { actions.onCategory(null) }) }
                    items(state.categories, key = { it.id }) { category ->
                        val locked = category.id in state.lockedCategoryIds
                        CategoryPill(
                            category.name,
                            onClick = { actions.onCategory(category) },
                            leadingIcon = if (locked) ({ Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(18.dp)) }) else null,
                        )
                    }
                }
            }
        }
        // On a TV, the first card of the first shelf takes the focus when the tab opens.
        val first = when {
            state.continueWatching.isNotEmpty() -> "resume"
            state.myList.isNotEmpty() -> "myList"
            state.awardWinners.isNotEmpty() -> "awards"
            else -> state.rows.firstOrNull()?.category?.id
        }
        if (state.continueWatching.isNotEmpty()) {
            item {
                Shelf(stringResource(R.string.continue_watching), metrics) {
                    itemsIndexed(state.continueWatching, key = { _, it -> it.key }) { index, item ->
                        ContinueCard(item, metrics.wideCardWidth, onClick = { actions.onResume(item) }, initialFocus = first == "resume" && index == 0, onRemove = { actions.onRemoveResume(item) }, onOpen = if (item.hasPage) ({ actions.onOpenResume(item) }) else null)
                    }
                }
            }
        }
        if (state.myList.isNotEmpty()) {
            item {
                Shelf(stringResource(R.string.my_list), metrics, onSeeAll = actions.onSeeAllMyList) {
                    itemsIndexed(state.myList.take(rowLength), key = { _, it -> it.key }) { index, item ->
                        PosterCard(item, metrics.posterWidth, onClick = { actions.onOpen(item) }, focusKey = "myList:${item.key}", initialFocus = first == "myList" && index == 0)
                    }
                    if (television) item(key = "seeAll") { TvSeeAllCard(onClick = actions.onSeeAllMyList, tall = true, width = metrics.posterWidth, modifier = Modifier.focusMemory("myList:seeAll")) }
                }
            }
        }
        if (state.awardWinners.isNotEmpty()) {
            item {
                Shelf(stringResource(R.string.award_winners), metrics) {
                    itemsIndexed(state.awardWinners, key = { _, it -> it.key }) { index, item ->
                        PosterCard(item, metrics.posterWidth, onClick = { actions.onOpen(item) }, focusKey = "awards:${item.key}", initialFocus = first == "awards" && index == 0)
                    }
                }
            }
        }
        items(state.rows, key = { it.category.id }) { row ->
            Shelf(row.category.name, metrics, onSeeAll = { actions.onCategory(row.category) }) {
                itemsIndexed(row.items.take(rowLength), key = { _, it -> it.key }) { index, item ->
                    PosterCard(
                        item,
                        metrics.posterWidth,
                        onClick = { actions.onOpen(item) },
                        focusKey = "${row.category.id}:${item.key}",
                        initialFocus = first == row.category.id && index == 0,
                    )
                }
                if (television) item(key = "seeAll") { TvSeeAllCard(onClick = { actions.onCategory(row.category) }, tall = true, width = metrics.posterWidth, modifier = Modifier.focusMemory("${row.category.id}:seeAll")) }
            }
        }
        if (state.rows.isEmpty()) {
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
    /** Whether finished movies are left out; null where it does not apply (series, My List). */
    hideWatched: Boolean? = null,
    onHideWatched: (Boolean) -> Unit = {},
) {
    var sorting by remember { mutableStateOf(false) }
    val metrics = rememberShelfMetrics()
    val leaveField = rememberLeaveField()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (metrics.compact) 108.dp else 150.dp),
        contentPadding = PaddingValues(start = metrics.gutter, top = tvTopBarSpace(), end = metrics.gutter, bottom = 32.dp),
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
                        hideWatched?.let { hidden ->
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.hide_watched)) },
                                leadingIcon = { if (hidden) Icon(Icons.Rounded.Check, contentDescription = null) },
                                onClick = {
                                    sorting = false
                                    onHideWatched(!hidden)
                                },
                                modifier = Modifier.semantics { selected = hidden }.testTag("hideWatched"),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                windowInsets = WindowInsets(0),
            )
        }
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            val field = rememberTvTextField()
            OutlinedTextField(
                value = filter,
                onValueChange = onFilter,
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.filter)) },
                // Done, as before; on a TV, on to the posters.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (!leaveField()) defaultKeyboardAction(ImeAction.Done) }),
                readOnly = field.readOnly,
                modifier = Modifier.fillMaxWidth().then(field.modifier),
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

/** A category as a grey pill: no outline, white words; a TV's focus turns it white. */
@Composable
internal fun CategoryPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, leadingIcon: (@Composable () -> Unit)? = null) {
    AssistChip(
        onClick = onClick,
        label = { Text(text, maxLines = 1) },
        leadingIcon = leadingIcon,
        modifier = modifier,
        shape = CircleShape,
        border = null,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            labelColor = MaterialTheme.colorScheme.onSurface,
            leadingIconContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}
