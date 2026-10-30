package com.markedoe.ondo.ui.tv

import android.icu.text.ListFormatter
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.markedoe.ondo.ui.theme.OndoColors
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.markedoe.ondo.R
import com.markedoe.ondo.ui.TagSuggestion
import com.markedoe.ondo.ui.components.HeroPage
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.components.PlayIcon
import com.markedoe.ondo.ui.model.ResumeItem
import com.markedoe.ondo.ui.screens.HomeActions
import com.markedoe.ondo.ui.screens.HomeUiState
import com.markedoe.ondo.ui.screens.myListState
import com.markedoe.ondo.ui.updatedAgoText
import kotlinx.coroutines.delay

/** The featured titles' frame takes about half the screen's height, under the top bar: the first row shows below. */
private const val HERO_SHARE = 0.52f
private val HeroShape = RoundedCornerShape(16.dp)
private const val HERO_ADVANCE_MS = 8_000L


/**
 * Home on a TV: the featured titles in a rounded frame under the top bar, their words and buttons inside it, then
 * rows of cards; the playlist and its last update at the bar's end, before Search. The focus starts on Play; past the last button (Right,
 * Left in a right-to-left language) turns to the next title, before the first one to the title before, both wrapping
 * around. The titles turn by themselves every
 * 8 s while the focus is elsewhere. While Home has nothing to focus (its catalogue replaced and coming again), the
 * remote can only be on the top bar: Play takes it back once it shows.
 */
@Composable
fun TvHomeScreen(
    state: HomeUiState,
    actions: HomeActions,
    autoAdvance: Boolean = LocalCarouselAutoAdvance.current,
    preparingKey: String? = null,
) {
    val titles = HomeTitles(
        movies = stringResource(R.string.movies_in_progress),
        series = stringResource(R.string.series_in_progress),
        myList = stringResource(R.string.my_list),
        newMovies = stringResource(R.string.new_movies),
        newSeries = stringResource(R.string.new_series),
        trending = stringResource(R.string.trending),
        channels = stringResource(if (state.channelsAreFavorites) R.string.favorite_channels else R.string.live_tv),
    )
    val (resumeSeries, resumeMovies) = state.continueWatching.partition { it.isSeries }
    val empty = state.hero.isEmpty() && state.tagSuggestion == null && state.continueWatching.isEmpty() && state.myList.isEmpty() &&
        state.channels.isEmpty() && state.newMovies.isEmpty() && state.newSeries.isEmpty() && state.trending.isEmpty()
    val memory = LocalFocusMemory.current
    LaunchedEffect(empty) { if (empty) memory?.given() }
    // A playlist with featured titles names itself in the top bar; one without has its name above the channels.
    TvTopBarNote(if (state.hero.isNotEmpty()) playlistLine(state) else null)
    val focusManager = LocalFocusManager.current
    // A card taken away hands the remote on before it goes: to the next card of its row, or the one before; the last
    // one, its row going with it, to the row below (or above). Not back to the carousel at the top.
    fun removeResume(row: List<ResumeItem>, item: ResumeItem) {
        val index = row.indexOfFirst { it.key == item.key }
        val neighbour = row.getOrNull(index + 1) ?: row.getOrNull(index - 1)
        if (neighbour != null) {
            memory?.handTo("resume:${neighbour.key}")
        } else if (!focusManager.moveFocus(FocusDirection.Down)) {
            focusManager.moveFocus(FocusDirection.Up)
        }
        actions.onRemoveResume(item)
    }
    LazyColumn(
        state = rememberTabListState(),
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("home"),
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item(key = "hero") {
            if (state.hero.isNotEmpty()) TvHero(state.hero, actions, autoAdvance, preparingKey) else TvHomeHeader(state)
        }
        state.tagSuggestion?.let { suggestion -> item(key = "suggestion") { TvTagSuggestion(suggestion, actions) } }
        if (resumeMovies.isNotEmpty()) {
            tvShelf(titles.movies, "shelfResumeMovies") {
                items(resumeMovies, key = { it.key }) { item ->
                    TvResumeCard(item, onClick = { actions.onResume(item) }, modifier = Modifier.focusMemory("resume:${item.key}"), preparing = item.key == preparingKey, onRemove = { removeResume(resumeMovies, item) }, onOpen = if (item.hasPage) ({ actions.onOpenResume(item) }) else null)
                }
            }
        }
        if (resumeSeries.isNotEmpty()) {
            tvShelf(titles.series, "shelfResumeSeries") {
                items(resumeSeries, key = { it.key }) { item ->
                    TvResumeCard(item, onClick = { actions.onResume(item) }, modifier = Modifier.focusMemory("resume:${item.key}"), preparing = item.key == preparingKey, onRemove = { removeResume(resumeSeries, item) }, onOpen = if (item.hasPage) ({ actions.onOpenResume(item) }) else null)
                }
            }
        }
        if (state.myList.isNotEmpty()) {
            tvShelf(titles.myList, "shelfMyList") {
                items(state.myList, key = { it.key }) { item -> TvPosterCard(item, onClick = { actions.onOpen(item) }, modifier = Modifier.focusMemory("myList:${item.key}")) }
                item(key = "seeAll") { TvSeeAllCard(onClick = actions.onSeeAllMyList, tall = true) }
            }
        }
        if (!state.hasVod) channelShelf(state, titles.channels, actions)
        if (state.newMovies.isNotEmpty()) {
            tvShelf(titles.newMovies, "shelfNewMovies") {
                items(state.newMovies, key = { it.key }) { item -> TvPosterCard(item, onClick = { actions.onOpen(item) }, modifier = Modifier.focusMemory("newMovies:${item.key}")) }
            }
        }
        if (state.newSeries.isNotEmpty()) {
            tvShelf(titles.newSeries, "shelfNewSeries") {
                items(state.newSeries, key = { it.key }) { item -> TvPosterCard(item, onClick = { actions.onOpen(item) }, modifier = Modifier.focusMemory("newSeries:${item.key}")) }
            }
        }
        if (state.trending.isNotEmpty()) {
            tvShelf(titles.trending, "shelfTrending") {
                items(state.trending, key = { it.key }) { item -> TvPosterCard(item, onClick = { actions.onOpen(item) }, modifier = Modifier.focusMemory("trending:${item.key}")) }
            }
        }
        if (state.hasVod) channelShelf(state, titles.channels, actions)
        if (!state.hasVod && state.channels.isEmpty() && !state.isLoading && !state.isRestoring) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.home_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = TvTokens.ContentStart, end = TvTokens.EdgeEnd),
                )
            }
        }
    }
}

private data class HomeTitles(val movies: String, val series: String, val myList: String, val newMovies: String, val newSeries: String, val trending: String, val channels: String)

private fun androidx.compose.foundation.lazy.LazyListScope.channelShelf(state: HomeUiState, title: String, actions: HomeActions) {
    if (state.channels.isEmpty()) return
    tvShelf(title, "shelfChannels") {
        items(state.channels, key = { it.channel.id }) { row ->
            TvChannelCard(row, onClick = { actions.onPlayChannel(row) }, modifier = Modifier.focusMemory("channel:${row.channel.id}"))
        }
        item(key = "seeAll") { TvSeeAllCard(onClick = actions.onSeeAllLive, tall = false) }
    }
}

@Composable
private fun TvHero(pages: List<HeroPage>, actions: HomeActions, autoAdvance: Boolean, preparingKey: String?) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val current = index.coerceIn(0, pages.lastIndex)
    val page = pages[current]
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(autoAdvance, hasFocus, pages.size, current) {
        if (!autoAdvance || hasFocus || pages.size < 2) return@LaunchedEffect
        delay(HERO_ADVANCE_MS)
        index = (current + 1) % pages.size
    }
    val ink = TvTokens.Ink
    val heroHeight = (LocalConfiguration.current.screenHeightDp.dp * HERO_SHARE).coerceAtLeast(260.dp)
    // Past the last button (Right, Left where the buttons run from the right): the next title. Before the first
    // one: the title before. Both wrap around, and the focus stays on the button.
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val onward = if (rtl) Key.DirectionLeft else Key.DirectionRight
    val backward = if (rtl) Key.DirectionRight else Key.DirectionLeft
    fun Modifier.turnsPage(key: Key, step: Int) = onPreviewKeyEvent { event ->
        val turn = event.key == key && pages.size > 1
        if (turn && event.type == KeyEventType.KeyDown) index = (current + step + pages.size) % pages.size
        turn
    }
    // The whole block, with the room above it for the top bar, comes into view with its buttons: back on it, the
    // page is at its top.
    Column(
        Modifier
            .fillMaxWidth()
            .bringIntoViewAsAWhole()
            .onFocusChanged { hasFocus = it.hasFocus }
            .testTag("hero")
            .padding(start = TvTokens.ContentStart, top = tvTopBarSpace(), end = TvTokens.EdgeEnd),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(heroHeight)
                .clip(HeroShape)
                .background(OndoColors.Surface)
                .testTag("heroFrame"),
        ) {
            LaunchedEffect(page.item.key) { actions.onShowBackdrop(page.item) }
            Crossfade(page, label = "heroArt") { shown ->
                TvArtwork(shown.backdropUrl, shown.item.posterUrl, shown.item.title, Modifier.fillMaxSize()) { url -> actions.onBackdropFailed(shown.item, url) }
            }
            // A shade on the side of the words, the rest of the picture left as it is.
            Box(Modifier.matchParentSize().background(startToEndGradient(0f to ink.copy(alpha = 0.72f), 0.45f to ink.copy(alpha = 0.35f), 0.75f to Color.Transparent)))
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to ink.copy(alpha = 0.55f))))
            // The words at the bottom left of the frame, the buttons under them. The buttons are measured first: a
            // long title or plot gives up its lines (the plot first), never the buttons' room.
            Column(
                Modifier
                    .fillMaxHeight()
                    .padding(start = 28.dp, top = 24.dp, bottom = 22.dp, end = if (pages.size > 1) 120.dp else 28.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Column(Modifier.weight(1f, fill = false).widthIn(max = 480.dp)) {
                    Text(
                        page.item.title,
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("heroTitle"),
                    )
                    Text(
                        page.meta,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    page.plot?.takeIf { it.isNotBlank() }?.let { HeroPlot(it, Modifier.weight(1f, fill = false)) }
                }
                Row(
                    Modifier.padding(top = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvActionButton(
                        onClick = { actions.onPlay(page.item) },
                        prominent = true,
                        // Before the first button: the title before.
                        modifier = Modifier.turnsPage(backward, -1).focusMemory("hero-play", initial = true).testTag("heroPlay"),
                    ) {
                        HeroButtonLabel(playLabel(page.resumable)) { PlayIcon(page.item.key == preparingKey, Modifier.size(20.dp), tint = LocalContentColor.current) }
                    }
                    val listState = myListState(page.inMyList)
                    TvActionButton(
                        onClick = { actions.onToggleMyList(page.item) },
                        modifier = Modifier.semantics { stateDescription = listState }.focusMemory("hero-mylist").testTag("heroMyList"),
                    ) {
                        HeroButtonLabel(stringResource(R.string.my_list)) {
                            Icon(if (page.inMyList) Icons.Rounded.Check else Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                    }
                    TvActionButton(
                        onClick = { actions.onOpen(page.item) },
                        // Past the last button: the next title.
                        modifier = Modifier.turnsPage(onward, 1).focusMemory("hero-info").testTag("heroInfo"),
                    ) {
                        HeroButtonLabel(stringResource(R.string.more_info)) { Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(20.dp)) }
                    }
                }
            }
            if (pages.size > 1) PageDots(pages.size, current, Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 40.dp))
        }
    }
}

/** A hero button's icon and its word on one line, the icon centred on it: the three buttons are the same height. */
@Composable
private fun RowScope.HeroButtonLabel(text: String, icon: @Composable () -> Unit) {
    Box(Modifier.size(20.dp).align(Alignment.CenterVertically), contentAlignment = Alignment.Center) { icon() }
    Spacer(Modifier.width(8.dp))
    Text(text, maxLines = 1, softWrap = false, modifier = Modifier.align(Alignment.CenterVertically))
}

/**
 * The plot in the room the title and the buttons leave: up to three whole lines (the last one ellipsed), none when
 * not even one fits, rather than a line cut through.
 */
@Composable
private fun HeroPlot(plot: String, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodyMedium
    val density = LocalDensity.current
    val gap = 8.dp
    BoxWithConstraints(modifier) {
        val line = with(density) { (if (style.lineHeight.isSp) style.lineHeight else style.fontSize * 1.4f).toDp() }
        val lines = if (constraints.hasBoundedHeight) ((maxHeight - gap) / line).toInt().coerceAtMost(3) else 3
        if (lines >= 1) {
            Text(
                plot,
                style = style,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = lines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = gap),
            )
        }
    }
}

/** Which of the featured titles is shown: a dash for the current one, dots for the others. */
@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            Box(
                Modifier
                    .height(4.dp)
                    .width(if (index == current) 22.dp else 7.dp)
                    .background(Color.White.copy(alpha = if (index == current) 1f else 0.35f), RoundedCornerShape(2.dp)),
            )
        }
    }
}

/** "Updated 5 minutes ago": when the playlist was last fetched from the provider. */
@Composable
private fun updatedAgo(state: HomeUiState): String? = state.updatedAt?.let { updatedAgoText(it, state.now) }

/** In the top bar, before Search: the playlist Home shows, and beneath it when it was updated (one line each). */
@Composable
private fun playlistLine(state: HomeUiState): String = listOfNotNull(state.playlistName.takeIf { it.isNotBlank() }, updatedAgo(state)).joinToString("\n")

/** A playlist without movies or series: its name and when it was updated, above the channels. */
@Composable
private fun TvHomeHeader(state: HomeUiState) {
    Column(Modifier.padding(start = TvTokens.ContentStart, top = 48.dp, end = TvTokens.EdgeEnd)) {
        Text(state.playlistName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        updatedAgo(state)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("lastUpdate"))
        }
    }
}

/** The one-time offer to keep only the viewer's languages, with its three answers. */
@Composable
private fun TvTagSuggestion(suggestion: TagSuggestion, actions: HomeActions) {
    val kept = remember(suggestion) { ListFormatter.getInstance().format(suggestion.keptNames) }
    val count = suggestion.kept.size + suggestion.hidden.size
    Column(
        Modifier
            .padding(start = TvTokens.ContentStart, end = TvTokens.EdgeEnd)
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .padding(20.dp)
            .testTag("tagSuggestion"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.tag_suggestion_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(pluralStringResource(R.plurals.tag_suggestion_message, count, count, kept), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Answered, the suggestion goes with the button that has the focus: Home takes the focus back (focusMemory).
            TvActionButton(onClick = { actions.onAcceptSuggestion(suggestion) }, modifier = Modifier.focusMemory("suggestion-keep").testTag("keepMyLanguages")) { Text(stringResource(R.string.keep_only_these)) }
            TvActionButton(onClick = actions.onChooseCategories, modifier = Modifier.focusMemory("suggestion-choose")) { Text(stringResource(R.string.choose)) }
            TvActionButton(onClick = actions.onDismissSuggestion, modifier = Modifier.focusMemory("suggestion-dismiss")) { Text(stringResource(R.string.not_now)) }
        }
    }
}

/** The end of a row: everything in it, on a page of its own ([width]: the row's poster width, if not the TV's). */
@Composable
fun TvSeeAllCard(onClick: () -> Unit, tall: Boolean, modifier: Modifier = Modifier, width: Dp? = null) {
    Card(
        onClick = onClick,
        modifier = modifier.width(width ?: if (tall) TvTokens.PosterWidth else 156.dp).aspectRatio(if (tall) 2f / 3f else 16f / 9f).testTag("seeAll"),
        shape = CardDefaults.shape(TvTokens.CardShape),
        colors = CardDefaults.colors(containerColor = Color.White.copy(alpha = 0.08f), focusedContainerColor = Color.White),
        scale = CardDefaults.scale(focusedScale = 1.08f),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.see_all), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}
