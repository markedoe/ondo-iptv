package com.markedoe.ondo.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.ui.components.HeroPage
import com.markedoe.ondo.ui.model.ChannelRow
import com.markedoe.ondo.ui.model.ResumeItem
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.model.TitleHeader
import com.markedoe.ondo.ui.screens.CategoryRow
import com.markedoe.ondo.ui.screens.HomeActions
import com.markedoe.ondo.ui.screens.HomeUiState
import com.markedoe.ondo.ui.screens.VodActions
import com.markedoe.ondo.ui.screens.VodScreen
import com.markedoe.ondo.ui.screens.VodUiState
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.ArtworkAlignment
import com.markedoe.ondo.ui.tv.LocalTitleHeaders
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TitleHeaders
import com.markedoe.ondo.ui.tv.TvArtworkAlignment
import com.markedoe.ondo.ui.tv.TvHomeScreen
import com.markedoe.ondo.ui.tv.LocalTvTopBar
import com.markedoe.ondo.ui.tv.TvNavigationFrame
import com.markedoe.ondo.ui.tv.TvTopBar
import com.markedoe.ondo.ui.tv.HIDE_WATCHED_CHIP_TAG
import com.markedoe.ondo.ui.tv.SEARCH_TAG
import com.markedoe.ondo.ui.tv.SORT_CHIP_TAG
import com.markedoe.ondo.data.PosterSort
import com.markedoe.ondo.ui.screens.BrowseKind
import com.markedoe.ondo.ui.screens.BrowseScreen
import com.markedoe.ondo.ui.screens.BrowseUiState
import com.markedoe.ondo.ui.screens.PosterGridScreen
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertIsSelected
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a TV's tab pages must keep, at Google's 960 × 540 dp: the header's backdrop from the screen's very top, cropped
 * towards its top (faces stay whole); no line of the playlist in the top bar, on Home, the Movies and Series tabs, a
 * category's and a genre's titles; every row type whole below the header (its name, its cards and their lines) once the remote is in it;
 * the viewer's path on the Movies and Series tabs (on the tab, the first row whole below the header showing its first
 * title; Down, that card and its title at once; Down again, the next row), a top bar of tabs, Search and Settings
 * only; a category's and a genre's titles with no row of their own above them (their name above the first row, their
 * order and Hide watched as chips at the end of that line, which Up from the first row reaches: Down from the tab lands
 * on the first poster, never on a chip); a category's grid five posters a row, scrolled by whole rows
 * (the focused row at its top, none cut above it), and a Movies row about five posters across; a focused card, grown, whole within its row at either end in both directions; and a continue watching card's
 * lines clear of its focus frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvLayoutRegressionTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun movie(id: String, name: String, rating: Double = 7.0) = ShowcaseItem.MovieItem(
        Movie(id = id, name = name, categoryId = "m-drama", streamUrl = "https://example.com/movie/$id.mp4", rating = rating, year = 2024, plot = "A keeper counts the ships that never come.", addedAt = 1_700_000_000L),
    )

    private val movies = (1..6).map { movie("$it", "Example Film $it") }
    private val drama = MediaCategory("m-drama", "Drama", ContentType.MOVIE)
    private val comedy = MediaCategory("m-comedy", "Comedy", ContentType.MOVIE)
    private val resume = (1..4).map { ResumeItem("movie:r$it", "Resumed Film $it", subtitle = "2026", imageUrl = null, progress = 0.4f, minutesLeft = 7) }
    private val channels = (1..6).map {
        ChannelRow(Channel(id = "c$it", name = "Example Channel $it", categoryId = "news", streamUrl = "https://example.com/live/c$it.m3u8"), nowTitle = "Morning News", nowProgress = 0.3f, nextTitle = null)
    }

    private val playlistName = "Example Playlist"

    private object Headers : TitleHeaders {
        override fun header(item: ShowcaseItem): Flow<TitleHeader> = flowOf(TitleHeader(item, genres = listOf("Drama"), tmdbRating = "7.2", plot = "A keeper counts the ships that never come."))

        override suspend fun load(item: ShowcaseItem) = Unit
    }

    /**
     * [content] as a tab page on a TV, under the top bar ([tabPage] false: a page beyond the tab, a category's or a
     * genre's titles). [onTab]: the page opened as the remote moved along the bar, the focus left on [tab] (the page's
     * first card never had it).
     */
    private fun show(tab: TopLevel, direction: LayoutDirection = LayoutDirection.Ltr, onTab: Boolean = false, tabPage: Boolean = true, content: @Composable () -> Unit) {
        var inputModes: InputModeManager? = null
        var bar: TvTopBar? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme {
                TelevisionFrame {
                    TvNavigationFrame(tab, showBar = true, tabPage = tabPage, onSelect = {}, onSearch = {}, onSettings = {}) {
                        bar = LocalTvTopBar.current
                        CompositionLocalProvider(LocalTitleHeaders provides Headers, LocalLayoutDirection provides direction, content = content)
                    }
                }
            }
        }
        compose.waitForIdle()
        if (onTab) {
            compose.runOnIdle { bar?.browsing = true }
            compose.onNodeWithTag(tab.tag).requestFocus()
        }
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.mainClock.autoAdvance = false
        advance(500)
    }

    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun moviesTab(state: VodUiState, direction: LayoutDirection = LayoutDirection.Ltr) = show(TopLevel.MOVIES, direction) { VodScreen(state, VodActions()) }

    private fun bounds(tag: String): DpRect = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun focusFirstCard(shelf: String) {
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(shelf))).onFirst().requestFocus()
        advance(1_000)
    }

    private fun assertWithin(what: String, inner: DpRect, top: Dp, bottom: Dp) {
        assertTrue("$what starts at ${inner.top}, above $top", inner.top >= top - 0.5.dp)
        assertTrue("$what ends at ${inner.bottom}, below $bottom", inner.bottom <= bottom + 0.5.dp)
    }

    @Test
    fun theHeadersBackdropStartsAtTheScreensTopAndKeepsItsUpperPart() {
        moviesTab(VodUiState(title = "Movies", categories = listOf(drama), rows = listOf(CategoryRow(drama, movies))))
        focusFirstCard("shelf:m-drama")
        assertEquals("the header from the screen's top", 0f, bounds("titleHeader").top.value, 0.5f)
        val art = compose.onNode(hasTestTag("tvArtwork") and hasAnyAncestor(hasTestTag("pageBackdrop")), useUnmergedTree = true)
        assertEquals("the picture from the screen's top", 0f, art.getUnclippedBoundsInRoot().top.value, 0.5f)
        assertBackdropToTheScreensBottom()
        val alignment = art.fetchSemanticsNode().config.getOrNull(ArtworkAlignment)
        assertEquals(TvArtworkAlignment, alignment)
        assertTrue("cropped towards its top", (alignment as BiasAlignment).verticalBias < 0f)
        // Over the picture, the bar's band is a shade: no ground-coloured edge across the picture under the bar.
        assertTrue(compose.onAllNodesWithTag("topBarScrim", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithTag("topBarBand", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun homesCarouselKeepsTheUpperPartOfItsPictureToo() {
        val featured = HeroPage(movies[0], meta = "Movie · 2024", inMyList = false, resumable = false)
        show(TopLevel.HOME) { TvHomeScreen(HomeUiState(playlistName = "Example Playlist", hero = listOf(featured), newMovies = movies, hasVod = true), HomeActions(), autoAdvance = false) }
        val art = compose.onNode(hasTestTag("tvArtwork") and hasAnyAncestor(hasTestTag("pageBackdrop")), useUnmergedTree = true)
        assertEquals(TvArtworkAlignment, art.fetchSemanticsNode().config.getOrNull(ArtworkAlignment))
        assertBackdropToTheScreensBottom()
        // The rows have the focus: the focused poster's picture, still behind the whole screen.
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("shelfNewMovies"))).onFirst().requestFocus()
        advance(1_000)
        assertBackdropToTheScreensBottom()
    }

    /**
     * The page's picture runs edge to edge, from the screen's top to its bottom, the rows over it: no band, no ground
     * coloured strip between the header and the posters.
     */
    private fun assertBackdropToTheScreensBottom() {
        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        val backdrop = bounds("pageBackdrop")
        assertEquals("the picture from the screen's top", screen.top.value, backdrop.top.value, 0.5f)
        assertEquals("the picture to the screen's bottom", screen.bottom.value, backdrop.bottom.value, 0.5f)
        assertEquals("the picture from the screen's left", screen.left.value, backdrop.left.value, 0.5f)
        assertEquals("the picture to the screen's right", screen.right.value, backdrop.right.value, 0.5f)
        val art = compose.onNode(hasTestTag("tvArtwork") and hasAnyAncestor(hasTestTag("pageBackdrop")), useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("the picture itself to the screen's bottom: $art", art.bottom >= screen.bottom - 0.5.dp)
    }

    /** Whether the top bar shows anything of the playlist: its name, when it was updated, a line of its own. */
    private fun playlistLineInTheBar(): Boolean = compose.onAllNodes(
        hasAnyAncestor(hasTestTag("topBar")) and (hasTestTag("lastUpdate") or hasText("Updated", substring = true) or hasText(playlistName, substring = true)),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun homeShowsNoPlaylistLineInTheBar() {
        val featured = HeroPage(movies[0], meta = "Movie · 2024", inMyList = false, resumable = false)
        val state = HomeUiState(playlistName = playlistName, updatedAt = NOW - 5 * 60_000L, now = NOW, hero = listOf(featured), newMovies = movies, hasVod = true)
        show(TopLevel.HOME) { TvHomeScreen(state, HomeActions(), autoAdvance = false) }
        assertTrue("the bar is there", compose.onAllNodesWithTag("topBar").fetchSemanticsNodes().isNotEmpty())
        assertFalse("no playlist line in the bar", playlistLineInTheBar())
    }

    @Test
    fun theMoviesTabShowsNoPlaylistLineAndItsHeadersWordsStartBelowTheBar() {
        moviesTab(VodUiState(title = "Movies", categories = listOf(drama), rows = listOf(CategoryRow(drama, movies))))
        focusFirstCard("shelf:m-drama")
        assertFalse("no playlist line in the bar", playlistLineInTheBar())
        val words = compose.onNode(hasTestTag("headerText") and hasAnyAncestor(hasTestTag("titleHeader")), useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("the header's words start below the bar", words.top >= bounds("tabSearch").bottom - 0.5.dp)
    }

    @Test
    fun theSeriesTabShowsNoPlaylistLine() {
        show(TopLevel.SERIES) { VodScreen(VodUiState(title = "Series", categories = listOf(drama), rows = listOf(CategoryRow(drama, movies))), VodActions()) }
        assertTrue("no Categories pill in the bar", compose.onAllNodesWithTag("categoryFilter").fetchSemanticsNodes().isEmpty())
        assertFalse("no playlist line in the bar", playlistLineInTheBar())
    }

    /** The Movies tab with one row of [shelf]'s type, and a row of posters below it. */
    private fun assertRowWholeUnderTheHeader(state: VodUiState, shelf: String) {
        moviesTab(state)
        focusFirstCard(shelf)
        assertTrue("a card of $shelf has the focus", compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag(shelf))).fetchSemanticsNodes().isNotEmpty())
        val header = bounds("titleHeader")
        val list = bounds("vod")
        assertEquals("the list right below the header", header.bottom.value, list.top.value, 0.5f)
        // The row's name and its cards with their lines: the shelf, whole.
        assertWithin(shelf, bounds(shelf), list.top, list.bottom)
    }

    @Test
    fun aContinueWatchingRowFitsWholeUnderTheHeader() = assertRowWholeUnderTheHeader(
        VodUiState(title = "Movies", categories = listOf(drama), continueWatching = resume, rows = listOf(CategoryRow(drama, movies))),
        "shelfResume",
    )

    @Test
    fun aMyListRowFitsWholeUnderTheHeader() = assertRowWholeUnderTheHeader(
        VodUiState(title = "Movies", categories = listOf(drama), myList = movies, rows = listOf(CategoryRow(drama, movies))),
        "shelfMyList",
    )

    @Test
    fun aCategoryRowFitsWholeUnderTheHeader() = assertRowWholeUnderTheHeader(
        VodUiState(title = "Movies", categories = listOf(drama, comedy), rows = listOf(CategoryRow(drama, movies), CategoryRow(comedy, movies.reversed()))),
        "shelf:m-drama",
    )

    /** The texts of the header's nodes tagged [tag]. */
    private fun headerTexts(tag: String): List<String> =
        compose.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag("titleHeader")), useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") }

    private fun focusedIn(shelf: String) = compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag(shelf))).fetchSemanticsNodes().isNotEmpty()

    private fun press(key: Key) {
        compose.onAllNodes(isFocused()).onLast().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** The header shows [title], with its facts line's rating and its synopsis. */
    private fun assertHeaderShows(title: String, step: String) {
        assertEquals("$step: the header's title", listOf(title), headerTexts("headerTitle"))
        assertEquals("$step: the header's rating", listOf("7.2"), headerTexts("headerRating"))
        assertEquals("$step: the header's synopsis", listOf("A keeper counts the ships that never come."), headerTexts("headerPlot"))
    }

    /** [shelf], its name and its cards, whole within the rows' list right below the header. */
    private fun assertWholeBelowTheHeader(shelf: String, step: String) {
        val list = bounds("vod")
        assertEquals("$step: the list right below the header", bounds("titleHeader").bottom.value, list.top.value, 0.5f)
        assertWithin("$step: $shelf", bounds(shelf), list.top, list.bottom)
    }

    /**
     * The remote's path the viewer takes on a tab ([tab], [state]): on the tab, the header shows the first card's title
     * ([first]) and the first row ([firstShelf]) shows whole below it, no chips between; Down goes straight to that card,
     * the header already on its title before any wait; Down again, the next row ([secondShelf]),
     * whole, the header following it (the row's card below the first one: one of [second]).
     */
    private fun assertTheDownPath(tab: TopLevel, state: VodUiState, firstShelf: String, first: String, secondShelf: String, second: List<String>) {
        show(tab, onTab = true) { VodScreen(state, VodActions()) }
        assertTrue("the focus on the tab", compose.onNodeWithTag(tab.tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true)
        assertHeaderShows(first, "on the tab")
        assertWholeBelowTheHeader(firstShelf, "on the tab")
        assertTrue(
            "no category chips between the header and the first row",
            compose.onAllNodes(hasText("All") and hasAnyAncestor(hasTestTag("vod")), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )

        // Down once: the first card, its title at once (the header's wait not over yet).
        press(Key.DirectionDown)
        advance(16)
        assertTrue("Down from the tab lands in $firstShelf", focusedIn(firstShelf))
        val card = compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(firstShelf))).onFirst().fetchSemanticsNode()
        assertTrue("on the row's first card", card.config.getOrNull(SemanticsProperties.Focused) == true)
        assertHeaderShows(first, "Down once")
        advance(1_000)
        assertHeaderShows(first, "Down once, settled")
        assertWholeBelowTheHeader(firstShelf, "Down once")

        // Down again: the next row, whole, the header on its first title.
        press(Key.DirectionDown)
        advance(1_500)
        assertTrue("Down again lands in $secondShelf", focusedIn(secondShelf))
        val focused = compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag(secondShelf))).onLast().fetchSemanticsNode()
        val title = focused.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull().orEmpty()
        assertTrue("a card of the row's first ones ($title)", title in second)
        assertHeaderShows(title, "Down twice")
        assertWholeBelowTheHeader(secondShelf, "Down twice")
    }

    private fun resumed(item: ShowcaseItem) = ResumeItem("resume:${item.key}", item.title, subtitle = "2026", imageUrl = null, progress = 0.4f, minutesLeft = 7, hasPage = true, showcase = item)

    private val comedies = (7..12).map { movie("$it", "Example Comedy $it") }

    private val moviesPath = VodUiState(
        title = "Movies",
        categories = listOf(drama, comedy),
        continueWatching = movies.take(3).map(::resumed),
        rows = listOf(CategoryRow(drama, movies.drop(3)), CategoryRow(comedy, comedies)),
    )

    @Test
    fun theMoviesTabFromItsTabDownTwice() = assertTheDownPath(TopLevel.MOVIES, moviesPath, "shelfResume", "Example Film 1", "shelf:m-drama", listOf("Example Film 4", "Example Film 5"))

    @Test
    fun theSeriesTabFromItsTabDownTwice() {
        fun series(id: String, name: String, category: String) = ShowcaseItem.SeriesItem(
            Series(id = id, name = name, categoryId = category, plot = "A keeper counts the ships that never come.", rating = 7.0, year = 2025),
        )
        val crime = MediaCategory("s-crime", "Crime", ContentType.SERIES)
        val nature = MediaCategory("s-nature", "Nature", ContentType.SERIES)
        val crimes = (1..6).map { series("c$it", "Example Series $it", crime.id) }
        val natures = (1..6).map { series("n$it", "Example Nature $it", nature.id) }
        val state = VodUiState(
            title = "Series",
            categories = listOf(crime, nature),
            continueWatching = crimes.take(2).map(::resumed),
            rows = listOf(CategoryRow(crime, crimes.drop(2)), CategoryRow(nature, natures)),
        )
        assertTheDownPath(TopLevel.SERIES, state, "shelfResume", "Example Series 1", "shelf:s-crime", listOf("Example Series 3", "Example Series 4"))
    }

    /**
     * The top bar holds the tabs, Search and Settings, nothing of the page's: Up from the first row reaches the tab,
     * Right along the bar goes from the last tab straight to Search.
     */
    @Test
    fun theTopBarHoldsTabsSearchAndSettingsOnly() {
        show(TopLevel.MOVIES, onTab = true) { VodScreen(moviesPath, VodActions()) }
        press(Key.DirectionDown)
        advance(500)
        assertTrue(focusedIn("shelfResume"))
        press(Key.DirectionUp)
        advance(300)
        assertEquals("Up from the first row lands on the tab", "tabMovies", focusedTag())
        assertTrue("no Categories pill", compose.onAllNodesWithTag("categoryFilter").fetchSemanticsNodes().isEmpty())
        val along = mutableListOf<String?>()
        var presses = 0
        while (focusedTag() != SEARCH_TAG) {
            check(presses++ < 8) { "Right along the bar never reached Search: $along" }
            press(Key.DirectionRight)
            advance(50)
            along += focusedTag()
        }
        assertTrue("only tabs before Search: $along", along.dropLast(1).all { it?.startsWith("tab") == true })
    }

    /** The texts under [matcher] (in the unmerged tree), as laid out. */
    private fun textLayouts(matcher: SemanticsMatcher): List<TextLayoutResult> =
        compose.onAllNodes(matcher and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                layouts.single()
            }

    private fun assertWhole(line: TextLayoutResult) {
        val text = line.layoutInput.text.text
        assertTrue("\"$text\" is cut short", !line.isLineEllipsized(0) && !line.hasVisualOverflow)
        assertTrue("\"$text\" is narrower than its words", line.size.width + 0.5f >= line.multiParagraph.intrinsics.maxIntrinsicWidth)
    }

    /** A category's name as providers write it, above its posters in French: whole, beside the chips. */
    @Test
    @Config(qualifiers = "+fr")
    fun aCategorysNameReadsWholeOnItsLabelLine() {
        show(TopLevel.MOVIES, tabPage = false) {
            PosterGridScreen("|FR| NOUVEAUTÉS", rated, filter = "", onFilter = {}, onOpen = {}, onBack = {}, hideWatched = false)
        }
        val label = textLayouts(hasTestTag("headerLabel"))
        assertEquals(listOf("|FR| NOUVEAUTÉS"), label.map { it.layoutInput.text.text })
        label.forEach(::assertWhole)
        textLayouts(hasAnyAncestor(hasTestTag(SORT_CHIP_TAG))).forEach(::assertWhole)
        assertTrue("the chips after the name", bounds("headerLabel").right <= bounds(SORT_CHIP_TAG).left + 0.5.dp)
    }

    /** Movies of a category, in the playlist's order: the best rated last but one. */
    private val rated = listOf(
        movie("21", "Example Film A", rating = 6.1),
        movie("22", "Example Film B", rating = 7.4),
        movie("23", "Example Film C", rating = 6.8),
        movie("24", "Example Film D", rating = 8.6),
        movie("25", "Example Film E", rating = 5.9),
    )

    /** The orders chosen with the Sort chip, as the shared setting receives them. */
    private val chosenOrders = mutableListOf<PosterSort>()

    /** Hide watched, turned on or off with its chip. */
    private val hiddenChoices = mutableListOf<Boolean>()

    /**
     * A category's titles ([drama]) as the app shows them on a TV: the category's name above the first row, its order
     * and Hide watched as chips at the end of that line, the grid sorted by the order. The focus is left on the Movies tab.
     */
    private fun categoryGrid() = show(TopLevel.MOVIES, onTab = true, tabPage = false) {
        var sort by remember { mutableStateOf(PosterSort.PLAYLIST) }
        var hidden by remember { mutableStateOf(false) }
        PosterGridScreen(
            "Drama", UiMappers.sorted(rated, sort), filter = "", onFilter = {}, onOpen = {}, onBack = {},
            sort = sort, onSort = { sort = it; chosenOrders += it },
            hideWatched = hidden, onHideWatched = { hidden = it; hiddenChoices += it },
        )
    }

    /** A genre's titles ([genre]) as the app shows them on a TV, opened from Home: its name above them, its order as a chip. */
    private fun genrePage(genre: String) = show(TopLevel.HOME, onTab = true, tabPage = false) {
        var sort by remember { mutableStateOf(PosterSort.PLAYLIST) }
        val state = BrowseUiState(BrowseKind.GENRE, genre, movies = UiMappers.sorted(rated, sort))
        BrowseScreen(state, onOpen = {}, onFestival = {}, onBack = {}, sort = sort, onSort = { sort = it; chosenOrders += it })
    }

    /** The posters of the page tagged [page], in their order. */
    private fun posters(page: String): List<String> = compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(page))).fetchSemanticsNodes()
        .mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() }

    private fun focusedTag(): String? = compose.onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull()?.config?.getOrNull(SemanticsProperties.TestTag)

    /** From a poster of the first row, Up to the Sort chip, then OK: its orders open. */
    private fun openTheSortMenu() {
        press(Key.DirectionUp)
        advance(300)
        assertEquals("Up from the first row: the Sort chip", SORT_CHIP_TAG, focusedTag())
        press(Key.DirectionCenter)
        advance(500)
    }

    /** In the open Sort menu, Rating, chosen with the remote. */
    private fun chooseRating() {
        var presses = 0
        while (focusedTag() != "sort-rating") {
            check(presses++ < 8) { "Down along the orders never reached Rating" }
            press(Key.DirectionDown)
            advance(50)
        }
        press(Key.DirectionCenter)
        advance(1_000)
    }

    /**
     * A category's titles: no row of their own above the posters (no Back, no title, no Sort, no filter), no playlist
     * line in the bar; Down from the tab lands on the first poster, its title in the header at once.
     */
    @Test
    fun aCategorysTitlesStartWithTheirPostersAndDownLandsOnTheFirst() {
        categoryGrid()
        fun inGrid(matcher: SemanticsMatcher) = compose.onAllNodes(matcher and hasAnyAncestor(hasTestTag("grid")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        assertFalse("no filter field", inGrid(hasSetTextAction()))
        assertFalse("no Filter placeholder", inGrid(hasText("Filter")))
        assertFalse("no Back button", inGrid(hasContentDescription("Back")))
        assertFalse("no title of its own", inGrid(hasText("Drama")))
        assertTrue("no Sort button", compose.onAllNodesWithTag("sort").fetchSemanticsNodes().isEmpty())
        assertFalse("no playlist line in the bar", playlistLineInTheBar())
        assertHeaderShows("Example Film A", "on the tab")
        assertEquals("the category's name above the first row", listOf("Drama"), headerTexts("headerLabel"))
        assertTrue("the label right above the posters", bounds("headerLabel").bottom <= bounds("grid").top + 0.5.dp)
        assertBackdropToTheScreensBottom()

        press(Key.DirectionDown)
        advance(16)
        val first = compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("grid"))).onFirst().fetchSemanticsNode()
        assertTrue("Down from the tab lands on the first poster, not on a chip (${focusedTag()})", first.config.getOrNull(SemanticsProperties.Focused) == true)
        assertHeaderShows("Example Film A", "Down once")
        val grid = bounds("grid")
        assertEquals("the posters right below the header", bounds("titleHeader").bottom.value, grid.top.value, 0.5f)
        assertWithin("the first poster", compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("grid"))).onFirst().getUnclippedBoundsInRoot(), grid.top, grid.bottom)
    }

    /**
     * A category's titles: "Sort: Playlist order" and Hide watched at the end of the label's line, right above the
     * posters. Up from any poster of the first row reaches the Sort chip; its orders open on the one shown; Rating,
     * chosen with the remote, puts the best rated first and goes to the shared setting; the focus comes back to the
     * chip. Right from it, Hide watched, which OK turns on.
     */
    @Test
    fun theSortChipsOnTheLabelLineSortThePosters() {
        categoryGrid()
        assertEquals("the playlist's order", "Example Film A", posters("grid").first())
        assertEquals(listOf("Sort: Playlist order"), chipTexts(SORT_CHIP_TAG))
        val label = bounds("headerLabel")
        val chip = bounds(SORT_CHIP_TAG)
        assertTrue("the chip on the label's line", chip.bottom >= label.bottom - 4.dp && chip.top <= label.bottom)
        assertTrue("the chip after the label", chip.left >= label.right - 0.5.dp)
        assertTrue("the chips above the posters", bounds(HIDE_WATCHED_CHIP_TAG).bottom <= bounds("grid").top + 0.5.dp)
        press(Key.DirectionDown)
        advance(300)
        assertEquals("Down from the tab: the first poster", "Example Film A", focusedTitle())
        // From the middle of the first row, Up goes to the chip too.
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        advance(300)
        assertEquals("Example Film C", focusedTitle())
        openTheSortMenu()
        assertEquals("the menu opens on the order shown", "sort-playlist", focusedTag())
        for (tag in PosterSort.entries.map { "sort-" + it.name.lowercase() }) {
            assertTrue("$tag in the menu", compose.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag("sortMenu"))).fetchSemanticsNodes().isNotEmpty())
        }
        compose.onNodeWithTag("sort-playlist").assertIsSelected()
        chooseRating()
        assertEquals(listOf(PosterSort.RATING), chosenOrders)
        assertTrue("the menu closed", compose.onAllNodesWithTag("sortMenu").fetchSemanticsNodes().isEmpty())
        assertEquals("the focus back on the chip", SORT_CHIP_TAG, focusedTag())
        assertEquals("the best rated first", "Example Film D", posters("grid").first())
        assertEquals(listOf("Sort: Rating"), chipTexts(SORT_CHIP_TAG))
        press(Key.DirectionRight)
        advance(300)
        assertEquals(HIDE_WATCHED_CHIP_TAG, focusedTag())
        press(Key.DirectionCenter)
        advance(300)
        assertEquals(listOf(true), hiddenChoices)
        compose.onNodeWithTag(HIDE_WATCHED_CHIP_TAG).assertIsSelected()
        // Down from the chips: back to the posters.
        press(Key.DirectionDown)
        advance(300)
        assertTrue("Down from the chips: the posters", focusedIn("grid"))
    }

    /** The words of the chip tagged [tag]. */
    private fun chipTexts(tag: String): List<String> =
        compose.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") }

    /** The title of the poster with the focus. */
    private fun focusedTitle(): String? = compose.onAllNodes(isFocused()).onLast().fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    /**
     * A genre's titles, opened from Home: the genre's name above the first row and its order as a chip (no Hide
     * watched there); Down from the tab lands on the first poster, Up from it on the chip, Up again on the tab shown.
     * The chip's orders open on the one shown; Rating puts the best rated first. No row of their own above the
     * posters, no playlist line.
     */
    @Test
    fun aGenresTitlesNameThemselvesAndSortWithTheirChip() {
        genrePage("Mystery")
        assertEquals("the genre's name above its titles", listOf("Mystery"), headerTexts("headerLabel"))
        assertTrue("no Hide watched on a genre's page", compose.onAllNodesWithTag(HIDE_WATCHED_CHIP_TAG).fetchSemanticsNodes().isEmpty())
        assertFalse("no playlist line in the bar", playlistLineInTheBar())
        assertTrue("no Back button", compose.onAllNodes(hasContentDescription("Back") and hasAnyAncestor(hasTestTag("browse")), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue("no Sort button", compose.onAllNodesWithTag("sort").fetchSemanticsNodes().isEmpty())
        press(Key.DirectionDown)
        advance(16)
        assertEquals("Down from the tab lands on the first poster", "Example Film A", focusedTitle())
        assertHeaderShows("Example Film A", "Down once")
        press(Key.DirectionUp)
        advance(300)
        assertEquals("Up from the first row: the Sort chip", SORT_CHIP_TAG, focusedTag())
        press(Key.DirectionUp)
        advance(300)
        assertEquals("Up again lands on the tab shown", "tabHome", focusedTag())
        press(Key.DirectionDown)
        advance(300)
        assertEquals("Down from the tab: the first poster again, not the chip", "Example Film A", focusedTitle())
        openTheSortMenu()
        assertEquals("the menu opens on the order shown", "sort-playlist", focusedTag())
        chooseRating()
        assertEquals(listOf(PosterSort.RATING), chosenOrders)
        assertEquals("the best rated first", "Example Film D", posters("browse").first())
    }

    /** A long category's titles: 24 rows of five. */
    private val longGrid = (0 until 120).map { movie("g$it", gridTitle(it)) }

    private fun gridTitle(index: Int) = "Grid Film %03d".format(index)

    /** The poster of [longGrid] at [index], with its bounds (unscaled: a poster without the focus). */
    private fun gridPoster(index: Int): DpRect =
        compose.onNode(hasClickAction() and hasContentDescription(gridTitle(index)) and hasAnyAncestor(hasTestTag("grid"))).getUnclippedBoundsInRoot()

    /** The posters of the grid now laid out, by their index in [longGrid], with their bounds in the root, cut by the grid. */
    private fun laidOutGridPosters(): Map<Int, Rect> =
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("grid"))).fetchSemanticsNodes()
            .mapNotNull { node ->
                val title = node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() ?: return@mapNotNull null
                title.removePrefix("Grid Film ").toIntOrNull()?.let { it to node.boundsInRoot }
            }.toMap()

    /**
     * The focus on [row] of [longGrid] (its first poster): the row whole, its top at the grid's top padding (8 dp, the
     * room a focused poster grows into, under the header), and no poster of a row above it showing at all.
     */
    private fun assertRowAtTheTop(row: Int, step: String) {
        val focused = compose.onAllNodes(isFocused()).onLast().fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
        assertEquals("$step: the focus on the row's first poster", gridTitle(row * 5), focused)
        val grid = bounds("grid")
        // Measured on the row's second poster: the focused one is drawn grown.
        val poster = gridPoster(row * 5 + 1)
        assertEquals("$step: row $row's top at the grid's top", (grid.top + 8.dp).value, poster.top.value, 1f)
        assertWithin("$step: row $row", poster, grid.top, grid.bottom)
        val gridTop = with(compose.density) { grid.top.toPx() }
        laidOutGridPosters().filterKeys { it < row * 5 }.forEach { (index, drawn) ->
            assertTrue("$step: ${gridTitle(index)}, above row $row, shows under the header ($drawn)", drawn.bottom <= gridTop + 1f)
        }
    }

    /**
     * A category's titles at 960 × 540 dp: five posters a row; the remote going down and up the grid moves it by whole
     * rows, the focused row whole at its top, the row above it gone (never cut under the header).
     */
    @Test
    fun aCategorysGridShowsFivePostersARowAndScrollsByWholeRows() {
        show(TopLevel.MOVIES, onTab = true, tabPage = false) {
            PosterGridScreen("Drama", longGrid, filter = "", onFilter = {}, onOpen = {}, onBack = {})
        }
        press(Key.DirectionDown)
        advance(1_000)
        // Measured on the second poster: the first, focused, is drawn grown.
        val first = gridPoster(1)
        (2..4).forEach { assertEquals("${gridTitle(it)} in the first row", first.top.value, gridPoster(it).top.value, 0.5f) }
        assertTrue("the sixth poster starts the second row", gridPoster(5).top >= first.bottom)
        assertTrue("five posters whole across the grid", gridPoster(4).right <= bounds("grid").right + 0.5.dp)
        assertTrue("posters bigger than a phone's (${first.right - first.left})", first.right - first.left >= 150.dp)
        assertRowAtTheTop(0, "Down from the tab")

        val rows = longGrid.size / 5
        for (row in 1 until rows) {
            press(Key.DirectionDown)
            advance(1_000)
            assertRowAtTheTop(row, "Down to row $row")
        }
        for (row in rows - 2 downTo 0) {
            press(Key.DirectionUp)
            advance(1_000)
            assertRowAtTheTop(row, "Up to row $row")
        }
    }

    /** A row of posters on the Movies tab at 960 × 540 dp: five whole across it, the sixth peeking at its end. */
    @Test
    fun aMoviesTabRowShowsAboutFivePostersAcross() {
        val ten = (1..10).map { movie("$it", "Example Film $it") }
        moviesTab(VodUiState(title = "Movies", categories = listOf(drama), rows = listOf(CategoryRow(drama, ten))))
        focusFirstCard("shelf:m-drama")
        val row = compose.onNode(hasTestTag("shelfRow") and hasAnyAncestor(hasTestTag("shelf:m-drama")), useUnmergedTree = true).getUnclippedBoundsInRoot()
        fun poster(n: Int) = compose.onNode(hasClickAction() and hasContentDescription("Example Film $n") and hasAnyAncestor(hasTestTag("shelf:m-drama"))).getUnclippedBoundsInRoot()
        (2..5).forEach { assertTrue("poster $it whole in the row", poster(it).right <= row.right + 0.5.dp) }
        val sixth = poster(6)
        assertTrue("the sixth peeking at the row's end: $sixth in $row", sixth.left < row.right - 16.dp && sixth.right > row.right)
        assertTrue("posters bigger than before (${sixth.right - sixth.left})", sixth.right - sixth.left >= 140.dp)
        assertWholeBelowTheHeader("shelf:m-drama", "a row of posters")
    }

    /** Home with one row of [shelf]'s type under its carousel, made smaller while the rows have the focus. */
    private fun assertHomeRowWhole(state: HomeUiState, shelf: String) {
        show(TopLevel.HOME) { TvHomeScreen(state, HomeActions(), autoAdvance = false) }
        focusFirstCard(shelf)
        val frame = bounds("heroFrame")
        val screen = compose.onNodeWithTag("home").getUnclippedBoundsInRoot()
        assertWithin(shelf, bounds(shelf), frame.bottom, screen.bottom)
    }

    private fun home(continueWatching: List<ResumeItem> = emptyList(), newMovies: List<ShowcaseItem> = emptyList(), channels: List<ChannelRow> = emptyList()) = HomeUiState(
        playlistName = playlistName,
        hero = listOf(HeroPage(movies[0], meta = "Movie · 2024", inMyList = false, resumable = false)),
        continueWatching = continueWatching,
        newMovies = newMovies,
        channels = channels,
        hasVod = true,
    )

    @Test
    fun homesContinueWatchingRowFitsWhole() = assertHomeRowWhole(home(continueWatching = resume), "shelfResumeMovies")

    @Test
    fun homesPosterRowFitsWhole() = assertHomeRowWhole(home(newMovies = movies), "shelfNewMovies")

    @Test
    fun homesChannelRowFitsWhole() = assertHomeRowWhole(home(channels = channels), "shelfChannels")

    /** Where [node] is drawn, in the root's pixels: with its focus scale, and not cut by its parents. */
    private fun drawnBounds(node: SemanticsNode): Rect {
        val coordinates = node.layoutInfo.coordinates
        return coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = false)
    }

    private fun focusedCard(shelf: String): SemanticsNode = compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag(shelf))).onLast().fetchSemanticsNode()

    /**
     * The focused card of [shelf], grown by its focus, is drawn whole within its row's visible part, at the row's
     * start and at its end, [cards] cards along ([direction]'s Right or Left along the row).
     */
    private fun assertFocusedCardsWithinTheRow(shelf: String, cards: Int, direction: LayoutDirection) {
        focusFirstCard(shelf)
        assertCardWithinTheRow(shelf, "the first card")
        val along = if (direction == LayoutDirection.Rtl) Key.DirectionLeft else Key.DirectionRight
        repeat(cards - 1) {
            compose.onAllNodes(isFocused()).onLast().performKeyInput { pressKey(along) }
            advance(600)
        }
        assertTrue("the focus stays in $shelf", compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag(shelf))).fetchSemanticsNodes().isNotEmpty())
        assertCardWithinTheRow(shelf, "the last card")
    }

    private fun assertCardWithinTheRow(shelf: String, which: String) {
        val card = focusedCard(shelf)
        val drawn = drawnBounds(card)
        // The scale shows in the bounds: else the check below would hold for a card drawn at its plain size.
        assertTrue("$which of $shelf is grown by its focus: ${drawn.width} for ${card.size.width}", drawn.width > card.size.width * 1.03f)
        val row = compose.onNode(hasTestTag("shelfRow") and hasAnyAncestor(hasTestTag(shelf)), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("$which of $shelf is cut at the left: $drawn in $row", drawn.left >= row.left - 0.5f)
        assertTrue("$which of $shelf is cut at the right: $drawn in $row", drawn.right <= row.right + 0.5f)
    }

    private val vodRows = VodUiState(title = "Movies", categories = listOf(drama), continueWatching = resume, rows = listOf(CategoryRow(drama, movies)))
    private val vodPosters = VodUiState(title = "Movies", categories = listOf(drama), rows = listOf(CategoryRow(drama, movies)))

    @Test
    fun aFocusedContinueWatchingCardIsWholeInItsRow() {
        moviesTab(vodRows)
        assertFocusedCardsWithinTheRow("shelfResume", resume.size, LayoutDirection.Ltr)
    }

    @Test
    fun aFocusedContinueWatchingCardIsWholeInItsRowRightToLeft() {
        moviesTab(vodRows, LayoutDirection.Rtl)
        assertFocusedCardsWithinTheRow("shelfResume", resume.size, LayoutDirection.Rtl)
    }

    @Test
    fun aFocusedPosterIsWholeInItsRow() {
        moviesTab(vodPosters)
        assertFocusedCardsWithinTheRow("shelf:m-drama", movies.size + 1, LayoutDirection.Ltr)
    }

    @Test
    fun aFocusedPosterIsWholeInItsRowRightToLeft() {
        moviesTab(vodPosters, LayoutDirection.Rtl)
        assertFocusedCardsWithinTheRow("shelf:m-drama", movies.size + 1, LayoutDirection.Rtl)
    }

    private fun assertHomesFocusedCardsWithinTheRow(state: HomeUiState, shelf: String, cards: Int, direction: LayoutDirection) {
        show(TopLevel.HOME, direction) { TvHomeScreen(state, HomeActions(), autoAdvance = false) }
        assertFocusedCardsWithinTheRow(shelf, cards, direction)
    }

    @Test
    fun homesFocusedContinueWatchingCardIsWholeInItsRow() = assertHomesFocusedCardsWithinTheRow(home(continueWatching = resume), "shelfResumeMovies", resume.size, LayoutDirection.Ltr)

    @Test
    fun homesFocusedContinueWatchingCardIsWholeInItsRowRightToLeft() = assertHomesFocusedCardsWithinTheRow(home(continueWatching = resume), "shelfResumeMovies", resume.size, LayoutDirection.Rtl)

    @Test
    fun homesFocusedPosterIsWholeInItsRow() = assertHomesFocusedCardsWithinTheRow(home(newMovies = movies), "shelfNewMovies", movies.size, LayoutDirection.Ltr)

    @Test
    fun homesFocusedPosterIsWholeInItsRowRightToLeft() = assertHomesFocusedCardsWithinTheRow(home(newMovies = movies), "shelfNewMovies", movies.size, LayoutDirection.Rtl)

    @Test
    fun homesFocusedChannelIsWholeInItsRow() = assertHomesFocusedCardsWithinTheRow(home(channels = channels), "shelfChannels", channels.size + 1, LayoutDirection.Ltr)

    @Test
    fun homesFocusedChannelIsWholeInItsRowRightToLeft() = assertHomesFocusedCardsWithinTheRow(home(channels = channels), "shelfChannels", channels.size + 1, LayoutDirection.Rtl)

    /** A continue watching card's lines keep clear of its focus frame and its corners, at both its sides. */
    private fun assertCardLinesInsideTheFrame(direction: LayoutDirection) {
        moviesTab(vodRows, direction)
        focusFirstCard("shelfResume")
        val card = drawnBounds(focusedCard("shelfResume"))
        val frame = with(compose.density) { 3.dp.toPx() }
        for (line in listOf(hasText("Resumed Film 1"), hasText("min left", substring = true))) {
            val text = drawnBounds(compose.onAllNodes(line and hasAnyAncestor(hasTestTag("shelfResume")), useUnmergedTree = true).onFirst().fetchSemanticsNode())
            assertTrue("a line ($direction) under the frame's start: $text in $card", text.left >= card.left + frame)
            assertTrue("a line ($direction) under the frame's end: $text in $card", text.right <= card.right - frame)
        }
    }

    @Test
    fun aContinueWatchingCardsLinesStayInsideItsFrame() = assertCardLinesInsideTheFrame(LayoutDirection.Ltr)

    @Test
    fun aContinueWatchingCardsLinesStayInsideItsFrameRightToLeft() = assertCardLinesInsideTheFrame(LayoutDirection.Rtl)

    private companion object {
        const val NOW = 1_760_000_000_000L
    }
}
