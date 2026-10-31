package com.markedoe.ondo.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.markedoe.ondo.ui.components.PosterCard
import com.markedoe.ondo.ui.components.rememberShelfMetrics
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.tv.focusFrame
import com.markedoe.ondo.ui.tv.tvTopBarSpace

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

/** The titles of the playlist sharing a value tapped on a movie's or a series' page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(state: BrowseUiState, onOpen: (ShowcaseItem) -> Unit, onFestival: (String) -> Unit, onBack: () -> Unit) {
    val metrics = rememberShelfMetrics()
    val title = when (state.kind) {
        BrowseKind.AWARD, BrowseKind.GENRE -> state.name
        BrowseKind.FESTIVAL -> awardGroupName(state.value)
        BrowseKind.YEAR -> stringResource(R.string.released_in_year, state.value)
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (metrics.compact) 108.dp else 150.dp),
        contentPadding = PaddingValues(start = metrics.gutter, top = tvTopBarSpace(), end = metrics.gutter, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).testTag("browse"),
    ) {
        fullWidth {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                windowInsets = WindowInsets(0),
            )
        }
        state.festival?.let { festival ->
            fullWidth {
                ListItem(
                    headlineContent = { Text(awardGroupName(festival), color = MaterialTheme.colorScheme.primary) },
                    leadingContent = { Icon(Icons.Rounded.EmojiEvents, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.focusFrame(RoundedCornerShape(12.dp), grow = 1f).clickable { onFestival(festival) }.testTag("festival"),
                )
            }
        }
        titles(R.string.movies, state.movies, onOpen)
        titles(R.string.series, state.series, onOpen)
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

private fun LazyGridScope.fullWidth(content: @Composable () -> Unit) = item(span = { GridItemSpan(maxLineSpan) }) { content() }

private fun LazyGridScope.titles(title: Int, posters: List<ShowcaseItem>, onOpen: (ShowcaseItem) -> Unit) {
    if (posters.isEmpty()) return
    fullWidth { Text(stringResource(title), style = MaterialTheme.typography.titleLarge) }
    items(posters, key = { it.key }) { item -> PosterCard(item, width = null, onClick = { onOpen(item) }, showsTitle = true) }
}
