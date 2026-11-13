package com.markedoe.ondo.ui.tv

import com.markedoe.ondo.ui.components.drawnIcon
import com.markedoe.ondo.ui.components.myListIcon
import android.icu.text.ListFormatter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.CompositionLocalProvider
import com.markedoe.ondo.ui.components.LocalShowsMyList
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
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
import androidx.compose.ui.unit.lerp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.markedoe.ondo.R
import com.markedoe.ondo.ui.GridSource
import com.markedoe.ondo.ui.selectionTitle
import com.markedoe.ondo.ui.TagSuggestion
import com.markedoe.ondo.ui.components.HeroPage
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.components.PlayIcon
import com.markedoe.ondo.ui.model.ResumeItem
import com.markedoe.ondo.ui.model.TitleHeader
import com.markedoe.ondo.core.library.CatalogueMatcher
import com.markedoe.ondo.core.library.RowOrder
import com.markedoe.ondo.ui.screens.HomeActions
import com.markedoe.ondo.ui.screens.HomeUiState
import com.markedoe.ondo.ui.screens.myListState
import com.markedoe.ondo.ui.updatedAgoText
import kotlinx.coroutines.delay

/** The featured titles' frame takes about half the screen's height, under the top bar: the first row shows below. */
private const val HERO_SHARE = 0.52f

/**
 * Its height while the remote is in the rows below, showing the poster with the focus: the header's title, facts and
 * two lines of synopsis inside it (126 dp, at least [COMPACT_PADDING] above and below), and on a 960 × 540 dp TV a whole row of
 * posters below it, its name at [HOME_ROW_LEAD] and the focused poster, grown, still clear of the 5 % at the screen's
 * bottom a TV may crop: 84 (top bar) + 146 + 50 + 222 (poster) + 9 (its growth) + 27 = 538 dp.
 */
private val HERO_COMPACT = 146.dp

/** The room above and below the header's words in the smaller frame. */
private val COMPACT_PADDING = 8.dp

/** Where the focused card of a row lands below the frame: under its row's name. */
private val HOME_ROW_LEAD = 50.dp
private val HeroShape = RoundedCornerShape(16.dp)
private const val HERO_ADVANCE_MS = 8_000L


/**
 * Home on a TV: the featured titles in a rounded frame under the top bar, their words and buttons inside it, then
 * rows of cards; the playlist and its last update at the bar's end, before Search. The focus starts on Play; past the last button (Right,
 * Left in a right-to-left language) turns to the next title, before the first one to the title before, both wrapping
 * around. The titles turn by themselves every
 * 8 s while the focus is elsewhere. The frame stays at the top: while the remote is in the rows below, it is smaller and
 * shows the poster with the focus (its picture, title, facts and synopsis), the carousel back once Up reaches its
 * buttons. While Home has nothing to focus (its catalogue replaced and coming again), the
 * remote can only be on the top bar: Play takes it back once it shows.
 */
@Composable
fun TvHomeScreen(
    state: HomeUiState,
    actions: HomeActions,
    autoAdvance: Boolean = LocalCarouselAutoAdvance.current,
    preparingKey: String? = null,
    /** When Ondo was last opened again from the launcher (0: not): each time, Home back at its top, Play focused. */
    launch: Long = 0L,
) {
    // The selections' names, read here: the rows below are no composable scope.
    val selectionTitles = state.selections.mapValues { selectionTitle(it.value) }
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
    // Nothing to focus: no carousel, no card, no row shown (the viewer may have hidden them all).
    val empty = state.hero.isEmpty() && state.tagSuggestion == null && state.shownRows.isEmpty()
    val memory = LocalFocusMemory.current
    LaunchedEffect(empty) { if (empty) memory?.given() }
    // A playlist without featured titles has its name and when it was updated above the channels.
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
    val hasHero = state.hero.isNotEmpty()
    // The featured titles not read yet (the saved catalogue still being read at a cold start, or the first download):
    // the frame's place is kept, with a wheel, so the rows don't move down when the carousel comes in.
    val heroPending = !hasHero && (state.isRestoring || (state.isLoading && state.updatedAt == null))
    val heroRoom = hasHero || heroPending
    // The poster with the focus in the rows, which the frame above shows while the rows have the focus.
    val focus = remember { FocusedTitle() }
    var rowsHaveFocus by remember { mutableStateOf(false) }
    var heroIndex by rememberSaveable { mutableIntStateOf(0) }
    val hero = if (hasHero) rememberHeroShown(state.hero, heroIndex, rowsHaveFocus, focus) else null
    // The frame's height: smaller while the remote is in the rows. Only the change between full and compact is
    // animated: the window's size, known from the first layout, is not.
    val windowHeight = LocalWindowInfo.current.containerDpSize.height
    val fullHeight = (windowHeight * HERO_SHARE).coerceAtLeast(260.dp)
    val compactHeight = HERO_COMPACT.coerceAtMost(fullHeight)
    val compactness by animateFloatAsState(if (rowsHaveFocus) 1f else 0f, tween(HERO_RESIZE_MS), label = "heroHeight")
    val heroHeight = { lerp(fullHeight, compactHeight, compactness) }
    // Kept here, not in the rows: the same list whether the featured titles are there yet or not.
    val listState = rememberTabListState()
    // A row coming above the first one while the list is at its top, the remote not in it (a selection's titles worked
    // out after Home was drawn): the list stays at its top, the new row shown, rather than kept on the row that was
    // first (a lazy list keeps its first item in place).
    val firstItem = if (!hasHero) "hero" else if (state.tagSuggestion != null) "suggestion" else state.shownRows.firstOrNull()
    val shownFirst = remember { FirstItem(firstItem) }
    if (firstItem != shownFirst.key) {
        shownFirst.key = firstItem
        Snapshot.withoutReadObservation {
            if (!rowsHaveFocus && listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) listState.requestScrollToItem(0)
        }
    }
    val bar = LocalTvTopBar.current
    // Whether the viewer has pressed a key on Home yet: until then, Home is theirs to start from its top.
    var viewerMoved by rememberSaveable { mutableStateOf(false) }
    // Where Home starts: Play; without featured titles (not read yet, or none), the first card of the first row.
    val firstRow = state.shownRows.firstOrNull()
    val initialKey: String? = when {
        hasHero -> HERO_PLAY
        // The carousel's place while its titles are read: the focus waits on it, not in the rows (a card with the focus
        // would bring its row up, which would then move when the carousel comes in).
        heroPending -> HERO_PENDING
        state.tagSuggestion != null -> "suggestion-keep"
        else -> when (firstRow) {
            null -> null
            RowOrder.HOME_CONTINUE_MOVIES -> resumeMovies.firstOrNull()?.let { "resume:${it.key}" }
            RowOrder.HOME_CONTINUE_SERIES -> resumeSeries.firstOrNull()?.let { "resume:${it.key}" }
            RowOrder.MY_LIST -> state.myList.firstOrNull()?.let { "myList:${it.key}" }
            RowOrder.HOME_CHANNELS -> state.channels.firstOrNull()?.let { "channel:${it.channel.id}" }
            RowOrder.HOME_NEW_MOVIES -> state.newMovies.firstOrNull()?.let { "newMovies:${it.key}" }
            RowOrder.HOME_NEW_SERIES -> state.newSeries.firstOrNull()?.let { "newSeries:${it.key}" }
            RowOrder.TRENDING -> state.trending.firstOrNull()?.let { "trending:${it.key}" }
            else -> state.selections[firstRow]?.let { row -> row.items.firstOrNull()?.let { "sel:${row.id}:${it.key}" } }
        }
    }
    // Home at its top, the focus where it starts ([initialKey]), the carousel full size: from the first frame, and
    // again each time what comes first changes before the viewer has pressed a key (the featured titles, or a row,
    // coming after Home was drawn: the catalogue or what the device kept of TMDb still being read at a cold start);
    // and on Ondo opened again from the TV's home screen. A row coming above the one with the focus never leaves the
    // list scrolled down, the rows above cut under the bar.
    suspend fun toTop() {
        com.markedoe.ondo.FocusTrace.log("home toTop start firstItem=$firstItem initialKey=$initialKey viewerMoved=$viewerMoved")
        listState.scrollToItem(0)
        // After the page's own focus effects (a card given its focus back as the page comes back).
        withFrameNanos { }
        com.markedoe.ondo.FocusTrace.log("home toTop after frame viewerMoved=$viewerMoved restore=${memory?.restore}")
        // A key pressed meanwhile (Down as a row came in): the viewer has moved, the remote stays where they took it.
        if (viewerMoved) return
        bar?.browsing = false
        com.markedoe.ondo.FocusTrace.log("home toTop handTo=$initialKey")
        initialKey?.let { memory?.handTo(it) } ?: memory?.given()
    }
    LaunchedEffect(firstItem, initialKey) {
        com.markedoe.ondo.FocusTrace.log("home toTop effect launched firstItem=$firstItem initialKey=$initialKey viewerMoved=$viewerMoved browsing=${bar?.browsing}")
        if (viewerMoved || bar?.browsing == true) return@LaunchedEffect
        toTop()
    }
    // TEMPORARY (claude/tvfocus-debug): each composition of Home, and Home entering or leaving the composition.
    SideEffect { com.markedoe.ondo.FocusTrace.log("home composed firstItem=$firstItem initialKey=$initialKey hero=${state.hero.size} pending=$heroPending rows=${state.shownRows}") }
    DisposableEffect(Unit) {
        com.markedoe.ondo.FocusTrace.log("home entered composition")
        onDispose { com.markedoe.ondo.FocusTrace.log("home left composition") }
    }
    // Back on the frame's buttons (Up from the first row), the rows at their top again: under the smaller frame the
    // first row came up to keep its focused card clear of the screen's bottom, and would stay so under the full one.
    var heroHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(heroHasFocus) {
        if (heroHasFocus && (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)) listState.animateScrollToItem(0)
    }
    var handledLaunch by rememberSaveable { mutableLongStateOf(launch) }
    LaunchedEffect(launch) {
        if (launch == 0L || launch == handledLaunch) return@LaunchedEffect
        handledLaunch = launch
        com.markedoe.ondo.FocusTrace.log("home launch effect: back to top")
        viewerMoved = false
        heroIndex = 0
        toTop()
    }
    // The picture runs under the top bar too: the bar's band is a shade over it.
    TvTopBarOverArt(on = hero != null)
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onPreviewKeyEvent {
                com.markedoe.ondo.FocusTrace.log("home key ${it.key} ${it.type} viewerMovedBefore=$viewerMoved restore=${memory?.restore}")
                if (it.type == KeyEventType.KeyDown) viewerMoved = true
                false
            }
            .testTag("home"),
    ) {
        // The carousel's picture, or the focused poster's, behind the whole screen, edge to edge: the frame's words and
        // the rows over it.
        if (hero != null) {
            TvPageBackdrop(hero.art.item, hero.art.backdropUrl, Modifier.matchParentSize()) { item, url -> actions.onBackdropFailed(item, url) }
        }
        Column(Modifier.fillMaxSize()) {
            if (hero != null) TvHero(state.hero, hero, heroIndex, { heroIndex = it }, actions, autoAdvance, preparingKey, rowsHaveFocus, heroHeight) { heroHasFocus = it }
            else if (heroPending) TvHeroPlaceholder(fullHeight)
            // The rows' list is as tall as it is under the smaller frame, the frame full size or not: its part below the
            // screen shows as the frame shrinks. The focus going down from the frame then scrolls the rows as their
            // place will be, not as it is for the moment the frame shrinks: the first row comes in whole, its name too,
            // rather than scrolled up to the top of a list that was about to grow.
            val rowsModifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onFocusChanged { rowsHaveFocus = it.hasFocus }
                .tallerBy { if (heroRoom) heroHeight() - compactHeight else 0.dp }
                .testTag("homeRows")
            val rows: @Composable () -> Unit = {
                LazyColumn(
                    state = listState,
                    modifier = rowsModifier,
                    contentPadding = PaddingValues(top = if (heroRoom) 18.dp else 0.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    if (!heroRoom) item(key = "hero") { TvHomeHeader(state) }
                    state.tagSuggestion?.let { suggestion ->
                        item(key = "suggestion") {
                            // Above the first row under the frame: kept for the remote's Up.
                            if (heroRoom) KeptForTheRemote()
                            TvTagSuggestion(suggestion, actions)
                        }
                    }
                    // The rows in the viewer's order (Settings → Row order), those hidden left out.
                    for (key in state.shownRows) when (key) {
                        RowOrder.HOME_CONTINUE_MOVIES -> tvShelf(titles.movies, "shelfResumeMovies") {
                            items(resumeMovies, key = { it.key }) { item ->
                                TvResumeCard(item, onClick = { actions.onResume(item) }, modifier = Modifier.focusMemory("resume:${item.key}"), preparing = item.key == preparingKey, onRemove = if (item.upNext) null else ({ removeResume(resumeMovies, item) }), onOpen = if (item.hasPage) ({ actions.onOpenResume(item) }) else null)
                            }
                        }
                        RowOrder.HOME_CONTINUE_SERIES -> tvShelf(titles.series, "shelfResumeSeries") {
                            items(resumeSeries, key = { it.key }) { item ->
                                TvResumeCard(item, onClick = { actions.onResume(item) }, modifier = Modifier.focusMemory("resume:${item.key}"), preparing = item.key == preparingKey, onRemove = if (item.upNext) null else ({ removeResume(resumeSeries, item) }), onOpen = if (item.hasPage) ({ actions.onOpenResume(item) }) else null)
                            }
                        }
                        RowOrder.MY_LIST -> tvShelf(titles.myList, "shelfMyList") {
                            items(state.myList, key = { it.key }) { item ->
                                CompositionLocalProvider(LocalShowsMyList provides true) { TvPosterCard(item, onClick = { actions.onOpen(item) }, modifier = Modifier.focusMemory("myList:${item.key}")) }
                            }
                            item(key = "seeAll") { TvSeeAllCard(onClick = actions.onSeeAllMyList, tall = true, modifier = Modifier.focusMemory("myList:seeAll")) }
                        }
                        RowOrder.HOME_CHANNELS -> channelShelf(state, titles.channels, actions)
                        RowOrder.HOME_NEW_MOVIES -> tvShelf(titles.newMovies, "shelfNewMovies") {
                            items(state.newMovies, key = { it.key }) { item -> TvPosterCard(item, onClick = { actions.onOpen(item) }, modifier = Modifier.focusMemory("newMovies:${item.key}")) }
                            item(key = "seeAll") { TvSeeAllCard(onClick = { actions.onSeeAllRow(GridSource.NEW, false) }, tall = true, modifier = Modifier.focusMemory("newMovies:seeAll")) }
                        }
                        RowOrder.HOME_NEW_SERIES -> tvShelf(titles.newSeries, "shelfNewSeries") {
                            items(state.newSeries, key = { it.key }) { item -> TvPosterCard(item, onClick = { actions.onOpen(item) }, modifier = Modifier.focusMemory("newSeries:${item.key}")) }
                            item(key = "seeAll") { TvSeeAllCard(onClick = { actions.onSeeAllRow(GridSource.NEW, true) }, tall = true, modifier = Modifier.focusMemory("newSeries:seeAll")) }
                        }
                        RowOrder.TRENDING -> tvShelf(titles.trending, "shelfTrending") {
                            items(state.trending, key = { it.key }) { item -> TvPosterCard(item, onClick = { actions.onOpen(item) }, modifier = Modifier.focusMemory("trending:${item.key}")) }
                        }
                        // A selection's row: the titles of its tags, a new pick every day.
                        else -> state.selections[key]?.let { row ->
                            tvShelf(selectionTitles[key].orEmpty(), "shelfSelection-${row.id}") {
                                items(row.items, key = { it.key }) { item -> TvPosterCard(item, onClick = { actions.onOpen(item) }, modifier = Modifier.focusMemory("sel:${row.id}:${item.key}")) }
                                item(key = "seeAll") { TvSeeAllCard(onClick = { actions.onSeeAllSelection(row.id) }, tall = true, modifier = Modifier.focusMemory("sel:${row.id}:seeAll")) }
                            }
                        }
                    }
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
            CompositionLocalProvider(LocalFocusedTitle provides focus) {
                // Under the frame, the focused card lands at the same place at every press, never down to the screen's
                // bottom: the rows run to it, the 5 % a TV may crop kept below the focused card.
                if (heroRoom) TvPinnedHeaderList(lead = HOME_ROW_LEAD, edge = TvTokens.EdgeTop, content = rows) else rows()
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
        item(key = "seeAll") { TvSeeAllCard(onClick = actions.onSeeAllLive, tall = false, modifier = Modifier.focusMemory("channels:seeAll")) }
    }
}

/**
 * [rowsHaveFocus]: the remote is in the rows below; the frame is smaller and shows [focus]'s poster once the remote rests
 * on it (a [HeroPage] of its own: its picture, then its title, facts and synopsis), the carousel standing still. Its
 * buttons stay where they are, unseen and taking no room, for Up to reach them: the carousel is back then.
 */
/** What Home's frame shows: the carousel's [page], or the poster with the focus in the rows ([header]); [art], its picture's. */
private class HeroShown(val page: HeroPage, val header: TitleHeader?, val art: HeroPage)

/**
 * What Home's frame shows of [pages] at [index]: while the rows have the focus ([rowsHaveFocus]), [focus]'s poster once
 * the remote rests on it (a [HeroPage] of its own for its picture; what it lacks fetched once the remote stays).
 */
@Composable
private fun rememberHeroShown(pages: List<HeroPage>, index: Int, rowsHaveFocus: Boolean, focus: FocusedTitle): HeroShown {
    val page = pages[index.coerceIn(0, pages.lastIndex)]
    val headers = LocalTitleHeaders.current
    val shownItem = rememberShownTitle(focus, keepLast = false)
    val focusedItem = shownItem?.takeIf { rowsHaveFocus }
    LoadWhenStill(focusedItem, headers)
    val header = rememberTitleHeader(focusedItem, headers)?.takeIf { focusedItem != null }
    // The poster with the focus as a page of the carousel's own, for its picture.
    val art = header?.let { HeroPage(it.item, meta = "", inMyList = false, resumable = false, backdropUrl = it.backdropUrl) } ?: page
    return HeroShown(page, header, art)
}

@Composable
private fun TvHero(
    pages: List<HeroPage>,
    shown: HeroShown,
    index: Int,
    onIndex: (Int) -> Unit,
    actions: HomeActions,
    autoAdvance: Boolean,
    preparingKey: String?,
    rowsHaveFocus: Boolean,
    heroHeight: () -> Dp,
    onFocus: (Boolean) -> Unit,
) {
    val current = index.coerceIn(0, pages.lastIndex)
    val page = shown.page
    val header = shown.header
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(autoAdvance, hasFocus, rowsHaveFocus, pages.size, current) {
        if (!autoAdvance || hasFocus || rowsHaveFocus || pages.size < 2) return@LaunchedEffect
        delay(HERO_ADVANCE_MS)
        onIndex((current + 1) % pages.size)
    }
    val compact = rowsHaveFocus
    // Past the last button (Right, Left where the buttons run from the right): the next title. Before the first
    // one: the title before. Both wrap around, and the focus stays on the button.
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val onward = if (rtl) Key.DirectionLeft else Key.DirectionRight
    val backward = if (rtl) Key.DirectionRight else Key.DirectionLeft
    fun Modifier.turnsPage(key: Key, step: Int) = onPreviewKeyEvent { event ->
        val turn = event.key == key && pages.size > 1
        if (turn && event.type == KeyEventType.KeyDown) onIndex((current + step + pages.size) % pages.size)
        turn
    }
    // The whole block, with the room above it for the top bar, comes into view with its buttons: back on it, the
    // page is at its top.
    Column(
        Modifier
            .fillMaxWidth()
            .bringIntoViewAsAWhole()
            .onFocusChanged {
                hasFocus = it.hasFocus
                onFocus(it.hasFocus)
            }
            .testTag("hero")
            .padding(start = TvTokens.ContentStart, top = tvTopBarSpace(), end = TvTokens.EdgeEnd),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(heroHeight())
                .clip(HeroShape)
                .testTag("heroFrame"),
        ) {
            // The picture is the page's, behind the whole screen ([TvPageBackdrop]): the frame holds the words only.
            LaunchedEffect(page.item.key) { actions.onShowBackdrop(page.item) }
            // The words at the bottom left of the frame, the buttons under them. The buttons are measured first: a
            // long title or plot gives up its lines (the plot first), never the buttons' room.
            Column(
                Modifier
                    .fillMaxHeight()
                    // Smaller, the frame keeps less room around the header's words.
                    .padding(start = 28.dp, top = if (compact) COMPACT_PADDING else 24.dp, bottom = if (compact) COMPACT_PADDING else 22.dp, end = if (pages.size > 1) 120.dp else 28.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                if (header != null) {
                    TitleHeaderText(header, Modifier.widthIn(max = 560.dp))
                } else Column(Modifier.weight(1f, fill = false).widthIn(max = 480.dp)) {
                    Text(
                        CatalogueMatcher.displayTitle(page.item.title),
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
                    Modifier.takesNoRoom(compact).graphicsLayer { alpha = if (compact) 0f else 1f }.padding(top = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvActionButton(
                        onClick = { actions.onPlay(page.item) },
                        prominent = true,
                        // Before the first button: the title before.
                        modifier = Modifier.turnsPage(backward, -1).focusMemory(HERO_PLAY, initial = true).testTag("heroPlay"),
                    ) {
                        HeroButtonLabel(playLabel(page.resumable)) { PlayIcon(page.item.key == preparingKey, Modifier.size(20.dp), tint = LocalContentColor.current) }
                    }
                    val listState = myListState(page.inMyList)
                    TvActionButton(
                        onClick = { actions.onToggleMyList(page.item) },
                        modifier = Modifier.semantics { stateDescription = listState }.focusMemory("hero-mylist").testTag("heroMyList"),
                    ) {
                        HeroButtonLabel(stringResource(R.string.my_list)) {
                            Icon(myListIcon(page.inMyList), contentDescription = null, modifier = Modifier.size(20.dp).drawnIcon(myListIcon(page.inMyList)))
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
            if (pages.size > 1 && header == null) PageDots(pages.size, current, Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 40.dp))
        }
    }
}

/** The key of the first item of Home's list, as last composed. */
private class FirstItem(var key: String?)

/** Play's place in Home's focus memory: where Home starts. */
private const val HERO_PLAY = "hero-play"
private const val HERO_PENDING = "hero-pending"

/**
 * The carousel's place while its titles are read: the full frame's size and corners (not smaller while the first card
 * has the focus meanwhile), a wheel in it, nothing to focus.
 */
@Composable
private fun TvHeroPlaceholder(height: Dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = TvTokens.ContentStart, top = tvTopBarSpace(), end = TvTokens.EdgeEnd)
            .focusMemory(HERO_PENDING, initial = true)
            .focusable()
            .testTag("heroPlaceholder"),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(HeroShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(36.dp), color = MaterialTheme.colorScheme.onSurface, strokeWidth = 3.dp)
        }
    }
}

/**
 * Measures the content [extra] taller than the room it is given, its top where it is: the part beyond hangs below
 * (off the screen at the page's bottom). The room it takes in its parent stays what it was given.
 */
private fun Modifier.tallerBy(extra: () -> Dp): Modifier = layout { measurable, constraints ->
    val more = extra().roundToPx().coerceAtLeast(0)
    if (!constraints.hasBoundedHeight || more == 0) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val height = constraints.maxHeight + more
    val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
    layout(placeable.width, constraints.maxHeight) { placeable.place(0, 0) }
}

/** How long the frame takes to grow or shrink as the remote goes from its buttons to the rows and back. */
private const val HERO_RESIZE_MS = 220

/**
 * While [enabled], this takes no room in its column: it is laid out at its size, over what is above it, so the
 * remote still finds it (Up from the rows), but the words above have the whole frame.
 */
private fun Modifier.takesNoRoom(enabled: Boolean): Modifier = if (!enabled) this else layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0))
    layout(placeable.width, 0) { placeable.place(0, -placeable.height) }
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
        modifier = modifier.width(width ?: if (tall) TvTokens.PosterWidth else TvTokens.ChannelCardWidth).aspectRatio(if (tall) 2f / 3f else 16f / 9f).testTag("seeAll"),
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
