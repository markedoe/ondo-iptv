package com.markedoe.ondo.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import com.markedoe.ondo.ui.components.LocalShowsMyList
import com.markedoe.ondo.ui.tv.rememberTabListState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.core.library.RowOrder
import com.markedoe.ondo.core.model.MediaCategory
import java.text.NumberFormat
import com.markedoe.ondo.data.PosterSort
import com.markedoe.ondo.ui.components.ContinueCard
import com.markedoe.ondo.ui.components.PosterCard
import com.markedoe.ondo.ui.components.Shelf
import com.markedoe.ondo.ui.components.rememberShelfMetrics
import com.markedoe.ondo.ui.model.ResumeItem
import com.markedoe.ondo.ui.model.PosterFacts
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.tv.focusMemory
import com.markedoe.ondo.ui.tv.TvSeeAllCard
import com.markedoe.ondo.ui.tv.rememberLeaveField
import com.markedoe.ondo.ui.tv.rememberTvTextField
import com.markedoe.ondo.ui.tv.TvHeaderPosterWidth
import com.markedoe.ondo.ui.tv.TvHeaderWideCardWidth
import com.markedoe.ondo.ui.tv.TvSortChoice
import com.markedoe.ondo.ui.tv.upToTheSortChip
import com.markedoe.ondo.ui.tv.TvHeaderAbove
import com.markedoe.ondo.ui.tv.TV_GRID_COLUMNS
import com.markedoe.ondo.ui.tv.tvGridBottomRoom
import com.markedoe.ondo.ui.tv.tvGridPosterHeight
import com.markedoe.ondo.ui.tv.TvGridScrolling
import com.markedoe.ondo.ui.tv.KeptForTheRemote
import com.markedoe.ondo.ui.tv.LocalTvTopBar
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.first

data class CategoryRow(val category: MediaCategory, val items: List<ShowcaseItem>) {
    /** Its key in the order of the rows ([RowOrder.categoryKey]). */
    val key: String get() = RowOrder.categoryKey(category.id, category.name)
}

data class VodUiState(
    val title: String,
    val categories: List<MediaCategory> = emptyList(),
    val lockedCategoryIds: Set<String> = emptySet(),
    val continueWatching: List<ResumeItem> = emptyList(),
    val myList: List<ShowcaseItem> = emptyList(),
    val rows: List<CategoryRow> = emptyList(),
    /** Movies that won an award (Palme d'Or, Oscars…), the most recent first. */
    val awardWinners: List<ShowcaseItem> = emptyList(),
    /** All movies (or series): the latest additions, each film once; See all opens the whole catalogue. */
    val all: List<ShowcaseItem> = emptyList(),
    /** How many titles the whole catalogue's grid lists (each film once, hidden ones left out). */
    val allCount: Int = 0,
    val newReleases: List<ShowcaseItem> = emptyList(),
    val trending: List<ShowcaseItem> = emptyList(),
    /**
     * The rows to show, by key ([RowOrder]), in the viewer's order (Settings → Row order), without those hidden; null:
     * the default order. A key whose row is empty shows nothing.
     */
    val order: List<String>? = null,
    val series: Boolean = false,
) {
    /** The rows shown, in their order: those with titles. */
    val shownRows: List<String>
        get() {
            val categories = rows.associateBy { it.key }
            val keys = order ?: (RowOrder.builtIns(series) + rows.map { it.key })
            return keys.distinct().filter { key ->
                when (key) {
                    RowOrder.ALL -> all.isNotEmpty()
                    RowOrder.CONTINUE_WATCHING -> continueWatching.isNotEmpty()
                    RowOrder.MY_LIST -> myList.isNotEmpty()
                    RowOrder.NEW_RELEASES -> newReleases.isNotEmpty()
                    RowOrder.TRENDING -> trending.isNotEmpty()
                    RowOrder.AWARD_WINNERS -> awardWinners.isNotEmpty()
                    else -> categories[key]?.items?.isNotEmpty() == true
                }
            }
        }

    /** The first title of a row: the one the focus starts on when the tab opens. */
    fun firstTitle(key: String?): ShowcaseItem? = when (key) {
        null -> null
        RowOrder.ALL -> all.firstOrNull()
        RowOrder.CONTINUE_WATCHING -> continueWatching.firstOrNull()?.showcase
        RowOrder.MY_LIST -> myList.firstOrNull()
        RowOrder.NEW_RELEASES -> newReleases.firstOrNull()
        RowOrder.TRENDING -> trending.firstOrNull()
        RowOrder.AWARD_WINNERS -> awardWinners.firstOrNull()
        else -> rows.firstOrNull { it.key == key }?.items?.firstOrNull()
    }
}

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

/**
 * Movies or Series tab: category chips (on a phone), continue watching, My List, award winners, one row per category.
 * On a TV, a header pinned above the rows shows the poster with the focus ([TvFocusHeader]), the posters about five
 * across: a row with its name and its cards' lines fits whole below it on a 960 × 540 dp screen. There, the rows are
 * always the tab's page, with no category chosen above them: a category's titles open from the See all card at the end
 * of its row, as a page of their own that Back closes. With the focus on the tab, the header shows the first card's
 * title and the first row shows whole below it; Down goes straight to that card.
 */
@Composable
fun VodScreen(state: VodUiState, actions: VodActions) {
    // The first card's title: the one the focus starts on when the tab opens.
    val fallback = state.firstTitle(state.shownRows.firstOrNull())
    // The focused poster below its row's name, at the same place at every press.
    TvHeaderAbove(fallback, lead = SHELF_TITLE_ROOM) { modifier, top, underHeader ->
        VodRows(state, actions, modifier, top, underHeader)
    }
}

/** The room a row's name takes above its posters ([Shelf]'s title line on a TV). */
private val SHELF_TITLE_ROOM = 52.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VodRows(state: VodUiState, actions: VodActions, modifier: Modifier, top: Dp, underHeader: Boolean) {
    val metrics = rememberShelfMetrics()
    val television = isTelevision()
    val width = if (underHeader) TvHeaderPosterWidth else metrics.posterWidth
    val wideWidth = if (underHeader) TvHeaderWideCardWidth else metrics.wideCardWidth
    // On a TV a row shows its first titles, then a See all card: the remote reaches it by going on along the row.
    val rowLength = if (television) TV_ROW_LENGTH else Int.MAX_VALUE
    val list = rememberTabListState()
    // Under the header, the row with the focus is brought whole into view.
    var focusedRow by remember { mutableStateOf<Any?>(null) }
    fun Modifier.row(key: Any): Modifier = if (underHeader) onFocusChanged { if (it.hasFocus) focusedRow = key } else this
    // A row that takes the focus while the page opens (its first card) is brought whole too: the focus then comes
    // before the list is laid out, and nothing scrolls it. Once a scroll in progress ends, a row still cut is moved up.
    LaunchedEffect(focusedRow, underHeader) {
        val key = focusedRow?.takeIf { underHeader } ?: return@LaunchedEffect
        withFrameNanos { }
        if (list.isScrollInProgress) snapshotFlow { list.isScrollInProgress }.first { !it }
        val info = list.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return@LaunchedEffect
        val top = item.offset - info.viewportStartOffset
        if (top < 0 || top + item.size > info.viewportSize.height) list.animateScrollToItem(item.index)
    }
    LazyColumn(
        state = list,
        modifier = modifier.testTag("vod"),
        verticalArrangement = Arrangement.spacedBy(if (metrics.compact) 24.dp else 32.dp),
        contentPadding = PaddingValues(top = top, bottom = 32.dp),
    ) {
        // On a TV the top bar names the tab and holds Search: the page starts with its first row, right below the
        // header, which Down from the tab reaches at once.
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
            item(key = "vod:chips") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = metrics.gutter),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
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
        // The rows in the viewer's order (Settings → Row order); on a TV, the first card of the first one takes the
        // focus when the tab opens.
        val shown = state.shownRows
        val first = shown.firstOrNull()
        val categoryRows = state.rows.associateBy { it.key }
        for (key in shown) when (key) {
            RowOrder.ALL -> item(key = "vod:all") {
                val count = remember(state.allCount) { NumberFormat.getIntegerInstance().format(state.allCount) }
                val name = stringResource(if (state.series) R.string.all_series_count else R.string.all_movies_count, count)
                Shelf(name, metrics, Modifier.row("vod:all").testTag("shelfAll"), onSeeAll = { actions.onCategory(null) }) {
                    itemsIndexed(state.all.take(rowLength), key = { _, it -> it.key }) { index, item ->
                        PosterCard(item, width, onClick = { actions.onOpen(item) }, focusKey = "all:${item.key}", initialFocus = first == key && index == 0)
                    }
                    if (television) item(key = "seeAll") { TvSeeAllCard(onClick = { actions.onCategory(null) }, tall = true, width = width, modifier = Modifier.focusMemory("all:seeAll").testTag("seeAllAll")) }
                }
            }
            RowOrder.CONTINUE_WATCHING -> item(key = "vod:resume") {
                Shelf(stringResource(R.string.continue_watching), metrics, Modifier.row("vod:resume").testTag("shelfResume")) {
                    itemsIndexed(state.continueWatching, key = { _, it -> it.key }) { index, item ->
                        ContinueCard(item, wideWidth, onClick = { actions.onResume(item) }, initialFocus = first == key && index == 0, onRemove = { actions.onRemoveResume(item) }, onOpen = if (item.hasPage) ({ actions.onOpenResume(item) }) else null)
                    }
                }
            }
            RowOrder.MY_LIST -> item(key = "vod:myList") {
                Shelf(stringResource(R.string.my_list), metrics, Modifier.row("vod:myList").testTag("shelfMyList"), onSeeAll = actions.onSeeAllMyList) {
                    itemsIndexed(state.myList.take(rowLength), key = { _, it -> it.key }) { index, item ->
                        CompositionLocalProvider(LocalShowsMyList provides true) {
                            PosterCard(item, width, onClick = { actions.onOpen(item) }, focusKey = "myList:${item.key}", initialFocus = first == key && index == 0)
                        }
                    }
                    if (television) item(key = "seeAll") { TvSeeAllCard(onClick = actions.onSeeAllMyList, tall = true, width = width, modifier = Modifier.focusMemory("myList:seeAll")) }
                }
            }
            RowOrder.NEW_RELEASES -> item(key = "vod:new") {
                Shelf(stringResource(if (state.series) R.string.new_series else R.string.new_movies), metrics, Modifier.row("vod:new").testTag("shelfNew")) {
                    itemsIndexed(state.newReleases, key = { _, it -> it.key }) { index, item ->
                        PosterCard(item, width, onClick = { actions.onOpen(item) }, focusKey = "new:${item.key}", initialFocus = first == key && index == 0)
                    }
                }
            }
            RowOrder.TRENDING -> item(key = "vod:trending") {
                Shelf(stringResource(R.string.trending), metrics, Modifier.row("vod:trending").testTag("shelfTrending")) {
                    itemsIndexed(state.trending, key = { _, it -> it.key }) { index, item ->
                        PosterCard(item, width, onClick = { actions.onOpen(item) }, focusKey = "trending:${item.key}", initialFocus = first == key && index == 0)
                    }
                }
            }
            RowOrder.AWARD_WINNERS -> item(key = "vod:awards") {
                Shelf(stringResource(R.string.award_winners), metrics, Modifier.row("vod:awards").testTag("shelfAwards")) {
                    itemsIndexed(state.awardWinners, key = { _, it -> it.key }) { index, item ->
                        PosterCard(item, width, onClick = { actions.onOpen(item) }, focusKey = "awards:${item.key}", initialFocus = first == key && index == 0)
                    }
                }
            }
            // Keyed apart from the items above: a category's id is the provider's, any word.
            else -> categoryRows[key]?.let { row ->
                item(key = "row:" + row.category.id) {
                    Shelf(row.category.name, metrics, Modifier.row("row:" + row.category.id).testTag("shelf:${row.category.id}"), onSeeAll = { actions.onCategory(row.category) }) {
                        itemsIndexed(row.items.take(rowLength), key = { _, it -> it.key }) { index, item ->
                            PosterCard(
                                item,
                                width,
                                onClick = { actions.onOpen(item) },
                                focusKey = "${row.category.id}:${item.key}",
                                initialFocus = first == key && index == 0,
                            )
                        }
                        if (television) item(key = "seeAll") { TvSeeAllCard(onClick = { actions.onCategory(row.category) }, tall = true, width = width, modifier = Modifier.focusMemory("${row.category.id}:seeAll")) }
                    }
                }
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

/**
 * Poster grid of a category (or of everything), with a filter and a sort menu. On a TV under the top bar, the posters
 * alone below the header: the page's name above the first row, its order and Hide watched as chips at the end of that
 * line (Up from the first row), Search finds a title, Back on the remote goes back to the rows; Down from the bar lands
 * on the first poster.
 */
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
    /** Each poster's rating and genres, by its key; null: the posters show their title and year only. */
    facts: Map<String, PosterFacts>? = null,
) {
    // On a TV, the top bar and the label's line hold what the page's own top row and filter would.
    val inTheBar = LocalTvTopBar.current != null
    val order = TvSortChoice(sort, onSort, hideWatched, onHideWatched).takeIf { inTheBar }
    // On a TV, a header pinned above the grid shows the poster with the focus: the posters show nothing more, five
    // across under the category's name, and the grid scrolls by whole rows (the focused one at the top, the one above
    // it gone).
    TvHeaderAbove(fallback = items.firstOrNull(), byRows = true, label = title.takeIf { inTheBar }, sort = order) { modifier, top, posterOnly ->
        val metrics = rememberShelfMetrics()
        val leaveField = rememberLeaveField()
        BoxWithConstraints(modifier) {
            val bottom = if (posterOnly) tvGridBottomRoom(maxWidth, maxHeight, metrics.gutter, GRID_GAP, top) else 32.dp
            val grid: @Composable () -> Unit = {
                LazyVerticalGrid(
                    columns = if (posterOnly) GridCells.Fixed(TV_GRID_COLUMNS) else GridCells.Adaptive(if (metrics.compact) 108.dp else 150.dp),
                    contentPadding = PaddingValues(start = metrics.gutter, top = top, end = metrics.gutter, bottom = bottom),
                    horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).testTag("grid"),
                ) {
                    if (!inTheBar) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            // Back and Sort, then the filter, above the first posters: kept for the remote's Up under a header.
                            if (posterOnly) KeptForTheRemote()
                            TopAppBar(
                                title = { Text(title, maxLines = 1) },
                                navigationIcon = {
                                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) }
                                },
                                actions = { SortButton(sort, onSort, hideWatched, onHideWatched) },
                                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                                windowInsets = WindowInsets(0),
                                // One stop as wide as the filter for Up from the posters: the filter, right above them, comes
                                // first, rather than the small Back button standing over the first column.
                                modifier = Modifier.focusGroup(),
                            )
                        }
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            if (posterOnly) KeptForTheRemote()
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
                    }
                    itemsIndexed(items, key = { _, it -> it.key }) { index, item ->
                        PosterCard(
                            item,
                            width = null,
                            onClick = { onOpen(item) },
                            // Up from the first row: the sort chips above it, from any column.
                            modifier = if (index < TV_GRID_COLUMNS) Modifier.upToTheSortChip() else Modifier,
                            showsTitle = !posterOnly,
                            facts = facts?.get(item.key),
                            showsFacts = facts != null && !posterOnly,
                        )
                    }
                    if (items.isEmpty()) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Text(stringResource(R.string.nothing_here), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            // On a TV, by whole rows.
            if (posterOnly) TvGridScrolling(top, tvGridPosterHeight(maxWidth, metrics.gutter, GRID_GAP), grid) else grid()
        }
    }
}

/** The room between two posters of a grid. */
private val GRID_GAP = 12.dp

/**
 * The Sort button of a page of posters and its menu: the orders of [PosterSort], the one shown ticked, then Hide
 * watched where [hideWatched] applies (null: it does not). On a TV, the remote reaches it above the posters.
 */
@Composable
internal fun SortButton(sort: PosterSort, onSort: (PosterSort) -> Unit, hideWatched: Boolean? = null, onHideWatched: (Boolean) -> Unit = {}) {
    var sorting by remember { mutableStateOf(false) }
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
                modifier = Modifier.semantics { selected = option == sort }.testTag("sort-" + option.name.lowercase()),
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
}

/** The name of an order in the Sort menus (a phone's, the TV's Sort chip). */
internal val PosterSort.label: Int
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
