package com.markedoe.ondo.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.data.PosterSort
import com.markedoe.ondo.ui.components.PosterCard
import com.markedoe.ondo.ui.components.rememberShelfMetrics
import com.markedoe.ondo.ui.model.PosterFacts
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.tv.focusFrame
import com.markedoe.ondo.ui.tv.TvHeaderAbove
import com.markedoe.ondo.ui.tv.TvSortChoice
import com.markedoe.ondo.ui.tv.upToTheSortChip
import com.markedoe.ondo.ui.tv.TV_GRID_COLUMNS
import com.markedoe.ondo.ui.tv.KeptForTheRemote
import com.markedoe.ondo.ui.tv.LocalTvTopBar

/** What a value of a movie's or a series' page stands for: an award, a festival, a year, a genre. */
enum class BrowseKind { AWARD, FESTIVAL, YEAR, GENRE }

/** The titles of the playlist sharing that value; [festival] is the festival or ceremony of an award. */
data class BrowseUiState(
    val kind: BrowseKind,
    val value: String,
    /** An award's name in the viewer's language; the value itself otherwise. */
    val name: String = value,
    val movies: List<ShowcaseItem> = emptyList(),
    val series: List<ShowcaseItem> = emptyList(),
    val festival: String? = null,
)

/** What a Browse page is called: the award's or the genre's name, the festival's, "Released in 2024". */
@Composable
fun browseTitle(state: BrowseUiState): String = when (state.kind) {
    BrowseKind.AWARD, BrowseKind.GENRE -> state.name
    BrowseKind.FESTIVAL -> awardGroupName(state.value)
    BrowseKind.YEAR -> stringResource(R.string.released_in_year, state.value)
}

/**
 * The titles of the playlist sharing a value tapped on a movie's or a series' page. On a TV under the top bar, no row
 * of its own above them: the page's name above the first row, its order as a chip at the end of that line (Up from the
 * first row), Back on the remote goes back, and Down from the bar lands on the first poster (an award's festival comes
 * after the titles).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    state: BrowseUiState,
    onOpen: (ShowcaseItem) -> Unit,
    onFestival: (String) -> Unit,
    onBack: () -> Unit,
    /** Each poster's rating and genres, by its key; null: the posters show their title and year only. */
    facts: Map<String, PosterFacts>? = null,
    /** The posters' order, the category grids' own ([PosterSort.PLAYLIST]: the page's own order). */
    sort: PosterSort = PosterSort.PLAYLIST,
    onSort: (PosterSort) -> Unit = {},
) {
    val metrics = rememberShelfMetrics()
    val title = browseTitle(state)
    // On a TV, the label's line names the page and holds its order.
    val inTheBar = LocalTvTopBar.current != null
    // On a TV, a header pinned above the grid shows the poster with the focus: the posters show nothing more.
    TvHeaderAbove(
        fallback = state.movies.firstOrNull() ?: state.series.firstOrNull(),
        label = title.takeIf { inTheBar },
        sort = TvSortChoice(sort, onSort).takeIf { inTheBar },
    ) { modifier, top, posterOnly ->
        LazyVerticalGrid(
            // On a TV under the header, five across, as a category's grid.
            columns = if (posterOnly) GridCells.Fixed(TV_GRID_COLUMNS) else GridCells.Adaptive(if (metrics.compact) 108.dp else 150.dp),
            contentPadding = PaddingValues(start = metrics.gutter, top = top, end = metrics.gutter, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = modifier.windowInsetsPadding(WindowInsets.statusBars).testTag("browse"),
        ) {
            if (!inTheBar) {
                fullWidth {
                    // Back and Sort above the first posters: kept for the remote's Up under a header.
                    if (posterOnly) KeptForTheRemote()
                    TopAppBar(
                        title = { Text(title, maxLines = 1) },
                        navigationIcon = {
                            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) }
                        },
                        actions = { SortButton(sort, onSort) },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                        windowInsets = WindowInsets(0),
                    )
                }
            }
            if (!inTheBar) {
                state.festival?.let { festival ->
                    fullWidth {
                        if (posterOnly) KeptForTheRemote()
                        FestivalLink(festival, onFestival)
                    }
                }
            }
            titles(R.string.movies, state.movies, onOpen, facts.takeUnless { posterOnly }, posterOnly, first = true)
            titles(R.string.series, state.series, onOpen, facts.takeUnless { posterOnly }, posterOnly, first = state.movies.isEmpty())
            // On a TV, the festival after the titles: Down from the bar goes straight to the first poster.
            if (inTheBar) state.festival?.let { festival -> fullWidth { FestivalLink(festival, onFestival) } }
            if (state.movies.isEmpty() && state.series.isEmpty()) {
                fullWidth {
                    Text(stringResource(R.string.person_nothing_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 24.dp))
                }
            }
            if (state.kind == BrowseKind.GENRE) {
                fullWidth {
                    Column {
                        Text(stringResource(R.string.genre_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** The festival or ceremony of an award's titles, which opens its page. */
@Composable
private fun FestivalLink(festival: String, onFestival: (String) -> Unit) {
    ListItem(
        headlineContent = { Text(awardGroupName(festival), color = MaterialTheme.colorScheme.primary) },
        leadingContent = { Icon(Icons.Rounded.EmojiEvents, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.focusFrame(RoundedCornerShape(12.dp), grow = 1f).clickable { onFestival(festival) }.testTag("festival"),
    )
}

private fun LazyGridScope.fullWidth(content: @Composable () -> Unit) = item(span = { GridItemSpan(maxLineSpan) }) { content() }

/** [posters] under their [title]; Up from the first row of the [first] titles reaches the page's sort chips. */
private fun LazyGridScope.titles(title: Int, posters: List<ShowcaseItem>, onOpen: (ShowcaseItem) -> Unit, facts: Map<String, PosterFacts>?, posterOnly: Boolean, first: Boolean) {
    if (posters.isEmpty()) return
    fullWidth { Text(stringResource(title), style = MaterialTheme.typography.titleLarge) }
    itemsIndexed(posters, key = { _, it -> it.key }) { index, item ->
        PosterCard(
            item,
            width = null,
            onClick = { onOpen(item) },
            modifier = if (first && index < TV_GRID_COLUMNS) Modifier.upToTheSortChip() else Modifier,
            showsTitle = !posterOnly,
            facts = facts?.get(item.key),
            showsFacts = facts != null,
        )
    }
}
