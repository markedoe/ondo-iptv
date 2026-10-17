package com.markedoe.ondo.ui.screens

import android.text.format.DateUtils
import android.icu.text.ListFormatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.ui.TagSuggestion
import com.markedoe.ondo.ui.components.Artwork
import com.markedoe.ondo.ui.components.ChannelLogo
import com.markedoe.ondo.ui.components.ContinueCard
import com.markedoe.ondo.ui.components.HeroCarousel
import com.markedoe.ondo.ui.components.HeroPage
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.components.PosterCard
import com.markedoe.ondo.ui.components.Shelf
import com.markedoe.ondo.ui.components.ShelfMetrics
import com.markedoe.ondo.ui.components.rememberShelfMetrics
import com.markedoe.ondo.ui.model.ChannelRow
import com.markedoe.ondo.ui.model.ResumeItem
import com.markedoe.ondo.ui.model.ShowcaseItem

data class HomeUiState(
    val playlistName: String,
    /** When the catalogue was last downloaded (null without one), and the time the state was made for. */
    val updatedAt: Long? = null,
    val now: Long = 0,
    val hero: List<HeroPage> = emptyList(),
    val continueWatching: List<ResumeItem> = emptyList(),
    val myList: List<ShowcaseItem> = emptyList(),
    /** By release year, not date added (see `Showcase.newMovies`). */
    val newMovies: List<ShowcaseItem> = emptyList(),
    val newSeries: List<ShowcaseItem> = emptyList(),
    val hasVod: Boolean = false,
    val channels: List<ChannelRow> = emptyList(),
    val channelsAreFavorites: Boolean = false,
    val isLoading: Boolean = false,
    /** The saved catalogue is still being read: the playlist is not called empty meanwhile. */
    val isRestoring: Boolean = false,
    /** "Show only your languages?", offered once on a playlist with categories for many languages. */
    val tagSuggestion: TagSuggestion? = null,
)

class HomeActions(
    val onOpen: (ShowcaseItem) -> Unit = {},
    val onPlay: (ShowcaseItem) -> Unit = {},
    val onToggleMyList: (ShowcaseItem) -> Unit = {},
    val onResume: (ResumeItem) -> Unit = {},
    val onSeeAllMyList: () -> Unit = {},
    val onSeeAllLive: () -> Unit = {},
    val onPlayChannel: (ChannelRow) -> Unit = {},
    val onPlaylists: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onSearch: () -> Unit = {},
    val onAcceptSuggestion: (TagSuggestion) -> Unit = {},
    val onChooseCategories: () -> Unit = {},
    val onDismissSuggestion: () -> Unit = {},
)

/**
 * Home: carousel of featured titles, then rows; live TV last (first on a live-only playlist). [preparingKey] is the
 * title or card whose Play is getting ready (a series' episodes loading): its button shows progress meanwhile.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    actions: HomeActions,
    autoAdvance: Boolean = LocalCarouselAutoAdvance.current,
    preparingKey: String? = null,
) {
    val metrics = rememberShelfMetrics()
    val hasVod = state.hasVod
    LazyColumn(
        modifier = Modifier.testTag("home"),
        verticalArrangement = Arrangement.spacedBy(if (metrics.compact) 26.dp else 34.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            if (state.hero.isNotEmpty()) {
                HeroCarousel(
                    pages = state.hero,
                    onOpen = actions.onOpen,
                    onPlay = actions.onPlay,
                    onToggleMyList = actions.onToggleMyList,
                    autoAdvance = autoAdvance,
                    preparingKey = preparingKey,
                    topBar = { HomeTopBar(state, actions, onArtwork = true) },
                )
            } else {
                Box(Modifier.windowInsetsPadding(WindowInsets.statusBars)) { HomeTopBar(state, actions, onArtwork = false) }
            }
        }
        state.tagSuggestion?.let { suggestion ->
            item { TagSuggestionCard(suggestion, actions, Modifier.padding(horizontal = metrics.gutter)) }
        }
        val (resumeSeries, resumeMovies) = state.continueWatching.partition { it.isSeries }
        if (resumeMovies.isNotEmpty()) {
            item { ResumeShelf(stringResource(R.string.movies_in_progress), resumeMovies, metrics, actions, preparingKey) }
        }
        if (resumeSeries.isNotEmpty()) {
            item { ResumeShelf(stringResource(R.string.series_in_progress), resumeSeries, metrics, actions, preparingKey) }
        }
        if (state.myList.isNotEmpty()) {
            item { PosterShelf(stringResource(R.string.my_list), state.myList, metrics, actions, actions.onSeeAllMyList) }
        }
        if (!hasVod) {
            item { ChannelShelf(state, metrics, actions) }
        }
        if (state.newMovies.isNotEmpty()) {
            item { PosterShelf(stringResource(R.string.new_movies), state.newMovies, metrics, actions) }
        }
        if (state.newSeries.isNotEmpty()) {
            item { PosterShelf(stringResource(R.string.new_series), state.newSeries, metrics, actions) }
        }
        if (hasVod) {
            item { ChannelShelf(state, metrics, actions) }
        }
        // Nothing is said while the saved catalogue is read or a download runs: it may well have content.
        if (!hasVod && state.channels.isEmpty() && !state.isLoading && !state.isRestoring) {
            item {
                Text(
                    text = stringResource(R.string.home_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = metrics.gutter),
                )
            }
        }
    }
}

@Composable
private fun ResumeShelf(title: String, items: List<ResumeItem>, metrics: ShelfMetrics, actions: HomeActions, preparingKey: String?) {
    Shelf(title, metrics) {
        items(items, key = { it.key }) {
            ContinueCard(it, metrics.wideCardWidth, onClick = { actions.onResume(it) }, preparing = it.key == preparingKey)
        }
    }
}

@Composable
private fun PosterShelf(title: String, items: List<ShowcaseItem>, metrics: ShelfMetrics, actions: HomeActions, onSeeAll: (() -> Unit)? = null) {
    Shelf(title, metrics, onSeeAll = onSeeAll) {
        items(items, key = { it.key }) { item -> PosterCard(item, metrics.posterWidth, onClick = { actions.onOpen(item) }) }
    }
}

@Composable
private fun ChannelShelf(state: HomeUiState, metrics: ShelfMetrics, actions: HomeActions) {
    if (state.channels.isEmpty()) return
    Shelf(
        title = stringResource(if (state.channelsAreFavorites) R.string.favorite_channels else R.string.live_tv),
        metrics = metrics,
        onSeeAll = actions.onSeeAllLive,
    ) {
        items(state.channels, key = { it.channel.id }) { row -> ChannelTile(row) { actions.onPlayChannel(row) } }
    }
}

@Composable
fun ChannelTile(row: ChannelRow, onClick: () -> Unit) {
    Column(Modifier.width(124.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(76.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            ChannelLogo(url = row.channel.logoUrl, name = row.channel.name)
        }
        Text(row.channel.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        row.nowTitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun HomeTopBar(state: HomeUiState, actions: HomeActions, onArtwork: Boolean) {
    val tint = if (onArtwork) Color.White else MaterialTheme.colorScheme.onBackground
    Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp)) {
        Column(
            modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(20.dp)).clickable(onClick = actions.onPlaylists).padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            state.updatedAt?.let { updatedAt ->
                // At least a minute ago: "0 minutes ago" reads oddly right after a refresh.
                val ago = DateUtils.getRelativeTimeSpanString(minOf(updatedAt, state.now - DateUtils.MINUTE_IN_MILLIS), state.now, DateUtils.MINUTE_IN_MILLIS)
                Text(
                    stringResource(R.string.updated_ago, ago),
                    color = tint.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier.testTag("lastUpdate"),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.playlistName, color = tint, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.playlists), tint = tint.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
            }
        }
        Row(Modifier.align(Alignment.CenterEnd)) {
            IconButton(onClick = actions.onSearch, modifier = Modifier.testTag("searchButton")) {
                Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.search), tint = tint)
            }
            IconButton(onClick = actions.onSettings, modifier = Modifier.testTag("settingsButton")) {
                Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.settings), tint = tint)
            }
        }
    }
}

/** Offered once after opening a playlist with categories for many languages and countries. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagSuggestionCard(suggestion: TagSuggestion, actions: HomeActions, modifier: Modifier = Modifier) {
    val kept = remember(suggestion) { ListFormatter.getInstance().format(suggestion.keptNames) }
    val count = suggestion.kept.size + suggestion.hidden.size
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
            .testTag("tagSuggestion"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.tag_suggestion_title), style = MaterialTheme.typography.titleMedium)
        Text(
            pluralStringResource(R.plurals.tag_suggestion_message, count, count, kept),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { actions.onAcceptSuggestion(suggestion) }, modifier = Modifier.testTag("keepMyLanguages")) {
                Text(stringResource(R.string.keep_only_these))
            }
            OutlinedButton(onClick = actions.onChooseCategories) { Text(stringResource(R.string.choose)) }
            TextButton(onClick = actions.onDismissSuggestion) { Text(stringResource(R.string.not_now)) }
        }
    }
}
