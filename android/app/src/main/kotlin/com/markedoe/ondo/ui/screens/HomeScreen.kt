package com.markedoe.ondo.ui.screens

import android.icu.text.ListFormatter
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.core.library.RowOrder
import com.markedoe.ondo.core.model.Profile
import com.markedoe.ondo.ui.GridSource
import com.markedoe.ondo.ui.TagSuggestion
import com.markedoe.ondo.ui.components.CardShape
import com.markedoe.ondo.ui.components.ChannelLogo
import com.markedoe.ondo.ui.components.ContinueCard
import com.markedoe.ondo.ui.components.HeroCarousel
import com.markedoe.ondo.ui.components.HeroPage
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.components.PosterCard
import com.markedoe.ondo.ui.components.Shelf
import com.markedoe.ondo.ui.components.ShelfMetrics
import com.markedoe.ondo.ui.components.pressScale
import com.markedoe.ondo.ui.components.rememberShelfMetrics
import com.markedoe.ondo.ui.model.ChannelRow
import com.markedoe.ondo.ui.model.ResumeItem
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.theme.OndoColors
import com.markedoe.ondo.ui.tv.focusFrame
import com.markedoe.ondo.ui.tv.focusMemory
import com.markedoe.ondo.ui.updatedAgoText
import com.markedoe.ondo.ui.SelectionRow
import com.markedoe.ondo.ui.selectionTitle

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
    /** Trending on TMDb this week and in the playlist (with the viewer's TMDb token). */
    val trending: List<ShowcaseItem> = emptyList(),
    val hasVod: Boolean = false,
    /**
     * Home's rows to show, by key ([RowOrder.homeBuiltIns]), in the viewer's order (Settings → Row order), without those
     * hidden; null: the default order. The carousel stays at the top, above them.
     */
    val order: List<String>? = null,
    /** The rows of the selections shown on Home, by row key (`sel:<id>`). */
    val selections: Map<String, SelectionRow> = emptyMap(),
    val channels: List<ChannelRow> = emptyList(),
    val channelsAreFavorites: Boolean = false,
    val isLoading: Boolean = false,
    /** The saved catalogue is still being read: the playlist is not called empty meanwhile. */
    val isRestoring: Boolean = false,
    /** "Show only your languages?", offered once on a playlist with categories for many languages. */
    val tagSuggestion: TagSuggestion? = null,
    /** The profile in use: its avatar next to Settings opens the switcher (docs/PROFILES.md). */
    val profile: Profile? = null,
) {
    /** Home's rows shown, by key, in their order: those with something in them (a phone's and a TV's Home alike). */
    val shownRows: List<String>
        get() = (order ?: RowOrder.homeBuiltIns(hasVod, collections = false)).distinct().filter { key ->
            when (key) {
                RowOrder.HOME_CONTINUE_MOVIES -> continueWatching.any { !it.isSeries }
                RowOrder.HOME_CONTINUE_SERIES -> continueWatching.any { it.isSeries }
                RowOrder.MY_LIST -> myList.isNotEmpty()
                RowOrder.HOME_NEW_MOVIES -> newMovies.isNotEmpty()
                RowOrder.HOME_NEW_SERIES -> newSeries.isNotEmpty()
                RowOrder.TRENDING -> trending.isNotEmpty()
                RowOrder.HOME_CHANNELS -> channels.isNotEmpty()
                else -> selections[key]?.items?.isNotEmpty() == true
            }
        }
}

class HomeActions(
    val onOpen: (ShowcaseItem) -> Unit = {},
    val onPlay: (ShowcaseItem) -> Unit = {},
    val onToggleMyList: (ShowcaseItem) -> Unit = {},
    val onResume: (ResumeItem) -> Unit = {},
    /** Long press on a "Continue watching" card, confirmed: the card goes. */
    val onRemoveResume: (ResumeItem) -> Unit = {},
    /** Long press on a "Continue watching" card, Go to: the movie's page, or the series' of the episode. */
    val onOpenResume: (ResumeItem) -> Unit = {},
    val onSeeAllMyList: () -> Unit = {},
    /** See all of a row Ondo picks (New movies, New series): every title of the row, in its order. */
    val onSeeAllRow: (GridSource, Boolean) -> Unit = { _, _ -> },
    /** See all of a selection's row: every title of it, in the day's order. */
    val onSeeAllSelection: (String) -> Unit = {},
    val onSeeAllLive: () -> Unit = {},
    val onPlayChannel: (ChannelRow) -> Unit = {},
    val onPlaylists: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onProfiles: () -> Unit = {},
    val onSearch: () -> Unit = {},
    val onAcceptSuggestion: (TagSuggestion) -> Unit = {},
    val onChooseCategories: () -> Unit = {},
    val onDismissSuggestion: () -> Unit = {},
    /** The carousel shows this title's wide picture (a TV): TMDb's is looked for when it has none that loads. */
    val onShowBackdrop: (ShowcaseItem) -> Unit = {},
    /** The backdrop at this address of the title did not load. */
    val onBackdropFailed: (ShowcaseItem, String) -> Unit = { _, _ -> },
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
                    topBar = { HomeTopBar(state, actions) },
                )
            } else {
                Box(Modifier.windowInsetsPadding(WindowInsets.statusBars)) { HomeTopBar(state, actions) }
            }
        }
        state.tagSuggestion?.let { suggestion ->
            item { TagSuggestionCard(suggestion, actions, Modifier.padding(horizontal = metrics.gutter)) }
        }
        val (resumeSeries, resumeMovies) = state.continueWatching.partition { it.isSeries }
        // The rows in the viewer's order (Settings → Row order), those hidden left out; each with its See all.
        for (key in state.shownRows) {
            item(key = "homeRow:$key") {
                when (key) {
                    RowOrder.HOME_CONTINUE_MOVIES -> ResumeShelf(stringResource(R.string.movies_in_progress), resumeMovies, metrics, actions, preparingKey, "shelfResumeMovies")
                    RowOrder.HOME_CONTINUE_SERIES -> ResumeShelf(stringResource(R.string.series_in_progress), resumeSeries, metrics, actions, preparingKey, "shelfResumeSeries")
                    RowOrder.MY_LIST -> PosterShelf(stringResource(R.string.my_list), state.myList, metrics, actions, "shelfMyList", actions.onSeeAllMyList)
                    RowOrder.HOME_NEW_MOVIES -> PosterShelf(stringResource(R.string.new_movies), state.newMovies, metrics, actions, "shelfNewMovies") { actions.onSeeAllRow(GridSource.NEW, false) }
                    RowOrder.HOME_NEW_SERIES -> PosterShelf(stringResource(R.string.new_series), state.newSeries, metrics, actions, "shelfNewSeries") { actions.onSeeAllRow(GridSource.NEW, true) }
                    RowOrder.TRENDING -> PosterShelf(stringResource(R.string.trending), state.trending, metrics, actions, "shelfTrending")
                    RowOrder.HOME_CHANNELS -> ChannelShelf(state, metrics, actions)
                    else -> state.selections[key]?.let { row ->
                        PosterShelf(selectionTitle(row), row.items, metrics, actions, "shelfSelection-${row.id}") { actions.onSeeAllSelection(row.id) }
                    }
                }
            }
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
private fun ResumeShelf(title: String, items: List<ResumeItem>, metrics: ShelfMetrics, actions: HomeActions, preparingKey: String?, tag: String) {
    Shelf(title, metrics, Modifier.testTag(tag)) {
        items(items, key = { it.key }) {
            ContinueCard(it, metrics.wideCardWidth, onClick = { actions.onResume(it) }, preparing = it.key == preparingKey, onRemove = if (it.upNext) null else ({ actions.onRemoveResume(it) }), onOpen = if (it.hasPage) ({ actions.onOpenResume(it) }) else null)
        }
    }
}

@Composable
private fun PosterShelf(title: String, items: List<ShowcaseItem>, metrics: ShelfMetrics, actions: HomeActions, tag: String, onSeeAll: (() -> Unit)? = null) {
    Shelf(title, metrics, Modifier.testTag(tag), onSeeAll = onSeeAll) {
        items(items, key = { it.key }) { item ->
            PosterCard(item, metrics.posterWidth, onClick = { actions.onOpen(item) }, focusKey = "$title:${item.key}")
        }
    }
}

@Composable
private fun ChannelShelf(state: HomeUiState, metrics: ShelfMetrics, actions: HomeActions) {
    if (state.channels.isEmpty()) return
    Shelf(
        title = stringResource(if (state.channelsAreFavorites) R.string.favorite_channels else R.string.live_tv),
        metrics = metrics,
        modifier = Modifier.testTag("shelfChannels"),
        onSeeAll = actions.onSeeAllLive,
    ) {
        items(state.channels, key = { it.channel.id }) { row -> ChannelTile(row) { actions.onPlayChannel(row) } }
    }
}

@Composable
fun ChannelTile(row: ChannelRow, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .width(140.dp)
            .pressScale(interaction)
            .focusFrame(CardShape)
            .focusMemory("channel:${row.channel.id}")
            .clip(CardShape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(bottom = 4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(CardShape)
                .background(OndoColors.SurfaceHigh),
        ) {
            Box(Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.Center) {
                ChannelLogo(url = row.channel.logoUrl, name = row.channel.name)
            }
            row.nowProgress?.let { progress ->
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(Color.White))
                }
            }
        }
        Text(row.channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        row.nowTitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun HomeTopBar(state: HomeUiState, actions: HomeActions) {
    val tint = MaterialTheme.colorScheme.onBackground
    Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp)) {
        Column(
            modifier = Modifier.align(Alignment.Center).focusFrame(RoundedCornerShape(20.dp), grow = 1.02f).clip(RoundedCornerShape(20.dp)).clickable(onClick = actions.onPlaylists).padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            state.updatedAt?.let { updatedAt ->
                Text(
                    updatedAgoText(updatedAt, state.now),
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
            state.profile?.let { profile ->
                val label = stringResource(R.string.switch_profile)
                IconButton(onClick = actions.onProfiles, modifier = Modifier.semantics { contentDescription = "$label, ${profile.name}" }.testTag("profileButton")) {
                    ProfileAvatar(profile, 30.dp, showLock = false)
                }
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
            .background(MaterialTheme.colorScheme.surfaceContainer)
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
