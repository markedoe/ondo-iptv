package com.markedoe.ondo.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.ui.components.PosterCard
import com.markedoe.ondo.ui.components.Shelf
import com.markedoe.ondo.ui.components.rememberShelfMetrics
import com.markedoe.ondo.ui.model.ChannelRow
import com.markedoe.ondo.ui.model.ShowcaseItem

data class SearchUiState(
    val query: String = "",
    val movies: List<ShowcaseItem> = emptyList(),
    val series: List<ShowcaseItem> = emptyList(),
    val channels: List<ChannelRow> = emptyList(),
)

/** Search across movies, series and channels of the current playlist. */
@Composable
fun SearchScreen(
    state: SearchUiState,
    onQuery: (String) -> Unit,
    onOpen: (ShowcaseItem) -> Unit,
    onPlayChannel: (ChannelRow) -> Unit,
) {
    val metrics = rememberShelfMetrics()
    LazyColumn(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).testTag("search"),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
    ) {
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.search_prompt)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = metrics.gutter).testTag("searchField"),
            )
        }
        if (state.movies.isNotEmpty()) {
            item {
                Shelf(stringResource(R.string.movies), metrics) {
                    items(state.movies, key = { it.key }) { PosterCard(it, metrics.posterWidth, onClick = { onOpen(it) }) }
                }
            }
        }
        if (state.series.isNotEmpty()) {
            item {
                Shelf(stringResource(R.string.series), metrics) {
                    items(state.series, key = { it.key }) { PosterCard(it, metrics.posterWidth, onClick = { onOpen(it) }) }
                }
            }
        }
        if (state.channels.isNotEmpty()) {
            item { Text(stringResource(R.string.channels), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = metrics.gutter)) }
            items(state.channels, key = { it.channel.id }) { row ->
                ListItem(
                    headlineContent = { Text(row.channel.name) },
                    supportingContent = row.nowTitle?.let { { Text(it) } },
                    modifier = Modifier.clickable { onPlayChannel(row) }.padding(horizontal = metrics.gutter - 16.dp),
                )
            }
        }
        if (state.query.isNotBlank() && state.movies.isEmpty() && state.series.isEmpty() && state.channels.isEmpty()) {
            item { Text(stringResource(R.string.no_results, state.query), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = metrics.gutter)) }
        }
        if (state.query.isBlank()) {
            item { Text(stringResource(R.string.search_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = metrics.gutter)) }
        }
    }
}
