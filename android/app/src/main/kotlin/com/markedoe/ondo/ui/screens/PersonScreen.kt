package com.markedoe.ondo.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

/** The movies and series of the playlist an actor or a director took part in. */
data class PersonUiState(
    val name: String = "",
    val movies: List<ShowcaseItem> = emptyList(),
    val series: List<ShowcaseItem> = emptyList(),
)

/** A name tapped in a movie's or series' credits: their movies and series among the pages already opened. */
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
        works(R.string.movies, state.movies, onOpen)
        works(R.string.series, state.series, onOpen)
        if (state.movies.isEmpty() && state.series.isEmpty()) {
            fullWidth {
                Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.person_nothing_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.person_nothing, state.name), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        fullWidth {
            Text(
                stringResource(R.string.person_opened_pages),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun LazyGridScope.fullWidth(content: @Composable () -> Unit) = item(span = { GridItemSpan(maxLineSpan) }) { content() }

private fun LazyGridScope.works(title: Int, posters: List<ShowcaseItem>, onOpen: (ShowcaseItem) -> Unit) {
    if (posters.isEmpty()) return
    fullWidth { Text(stringResource(title), style = MaterialTheme.typography.titleLarge) }
    items(posters, key = { it.key }) { item -> PosterCard(item, width = null, onClick = { onOpen(item) }, showsTitle = true) }
}
