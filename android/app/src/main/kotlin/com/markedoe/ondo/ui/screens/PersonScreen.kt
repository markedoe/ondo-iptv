package com.markedoe.ondo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.ui.components.PosterCard
import com.markedoe.ondo.ui.components.rememberShelfMetrics
import com.markedoe.ondo.ui.model.ShowcaseItem

/** The movies and series of the playlist an actor or a director took part in. */
data class PersonUiState(
    val name: String = "",
    val movies: List<ShowcaseItem> = emptyList(),
    val series: List<ShowcaseItem> = emptyList(),
    /** TMDb's credits were searched (with the viewer's token): all the person's titles of the playlist are listed. */
    val searchedOnline: Boolean = false,
    /** TMDb is being asked. */
    val searching: Boolean = false,
    val hasTmdbToken: Boolean = false,
)

/**
 * A name tapped in a movie's or series' credits: their movies and series among the pages already opened, and with the
 * viewer's TMDb token all those of the playlist TMDb credits them with.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonScreen(state: PersonUiState, onOpen: (ShowcaseItem) -> Unit, onBack: () -> Unit) {
    val metrics = rememberShelfMetrics()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (metrics.compact) 108.dp else 150.dp),
        contentPadding = PaddingValues(start = metrics.gutter, end = metrics.gutter, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).testTag("person"),
    ) {
        fullWidth {
            TopAppBar(
                title = { Text(state.name, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                windowInsets = WindowInsets(0),
            )
        }
        // While TMDb is asked, a bar under the name says the list is still growing (the titles already found show).
        if (state.searching && (state.movies.isNotEmpty() || state.series.isNotEmpty())) {
            fullWidth { LinearProgressIndicator(Modifier.fillMaxWidth().testTag("personSearching")) }
        }
        works(R.string.movies, state.movies, onOpen)
        works(R.string.series, state.series, onOpen)
        if (state.searching && state.movies.isEmpty() && state.series.isEmpty()) {
            fullWidth { Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.testTag("personSearching")) } }
        } else if (state.movies.isEmpty() && state.series.isEmpty()) {
            fullWidth {
                Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.person_nothing_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.person_nothing, state.name), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // Without TMDb's credits, only the pages already opened name their cast.
        if (!state.searchedOnline && !state.searching) {
            fullWidth {
                Text(
                    stringResource(if (state.hasTmdbToken) R.string.person_opened_pages else R.string.person_opened_pages_tmdb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("personHint"),
                )
            }
        }
    }
}

private fun LazyGridScope.fullWidth(content: @Composable () -> Unit) = item(span = { GridItemSpan(maxLineSpan) }) { content() }

private fun LazyGridScope.works(title: Int, posters: List<ShowcaseItem>, onOpen: (ShowcaseItem) -> Unit) {
    if (posters.isEmpty()) return
    fullWidth { Text(stringResource(title), style = MaterialTheme.typography.titleLarge) }
    items(posters, key = { it.key }) { item -> PosterCard(item, width = null, onClick = { onOpen(item) }, showsTitle = true) }
}
