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
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
import com.markedoe.ondo.ui.tv.PlaylistUpdate
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TitleHeaders
import com.markedoe.ondo.ui.tv.TvArtworkAlignment
import com.markedoe.ondo.ui.tv.TvHomeScreen
import com.markedoe.ondo.ui.tv.LocalTvTopBar
import com.markedoe.ondo.ui.tv.TvCategoryChoice
import com.markedoe.ondo.ui.tv.TvNavigationFrame
import com.markedoe.ondo.ui.tv.TvTopBar
import com.markedoe.ondo.ui.tv.TvTopBarCategories
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a TV's tab pages must keep, at Google's 960 × 540 dp: the header's backdrop from the screen's very top, cropped
 * towards its top (faces stay whole); the playlist and when it was updated at the top bar's end, on Home and on the
 * Movies tab; every row type whole below the header (its name, its cards and their lines) once the remote is in it;
 * the viewer's path on the Movies and Series tabs (on the tab, the first row whole below the header showing its first
 * title; Down, that card and its title at once; Down again, the next row), the Categories pill in the bar and its
 * picker; a focused card, grown, whole within its row at either end in both directions; and a continue watching card's
 * lines clear of its focus frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvLayoutRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun movie(id: String, name: String) = ShowcaseItem.MovieItem(
        Movie(id = id, name = name, categoryId = "m-drama", streamUrl = "https://example.com/movie/$id.mp4", rating = 7.0, year = 2024, plot = "A keeper counts the ships that never come.", addedAt = 1_700_000_000L),
    )

    private val movies = (1..6).map { movie("$it", "Example Film $it") }
    private val drama = MediaCategory("m-drama", "Drama", ContentType.MOVIE)
    private val comedy = MediaCategory("m-comedy", "Comedy", ContentType.MOVIE)
    private val resume = (1..4).map { ResumeItem("movie:r$it", "Resumed Film $it", subtitle = "2026", imageUrl = null, progress = 0.4f, minutesLeft = 7) }
    private val channels = (1..6).map {
        ChannelRow(Channel(id = "c$it", name = "Example Channel $it", categoryId = "news", streamUrl = "https://example.com/live/c$it.m3u8"), nowTitle = "Morning News", nowProgress = 0.3f, nextTitle = null)
    }

    private val playlist = PlaylistUpdate("Example Playlist", updatedAt = NOW - 5 * 60_000L, now = NOW)

    private object Headers : TitleHeaders {
        override fun header(item: ShowcaseItem): Flow<TitleHeader> = flowOf(TitleHeader(item, genres = listOf("Drama"), tmdbRating = "7.2", plot = "A keeper counts the ships that never come."))

        override suspend fun load(item: ShowcaseItem) = Unit
    }

    /**
     * [content] as a tab page on a TV: under the top bar, which shows [playlist] unless the page says otherwise. [onTab]:
     * the page opened as the remote moved along the bar, the focus left on [tab] (the page's first card never had it).
     */
    private fun show(tab: TopLevel, direction: LayoutDirection = LayoutDirection.Ltr, onTab: Boolean = false, content: @Composable () -> Unit) {
        var inputModes: InputModeManager? = null
        var bar: TvTopBar? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme {
                TelevisionFrame {
                    TvNavigationFrame(tab, showBar = true, onSelect = {}, onSearch = {}, onSettings = {}, playlist = playlist) {
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
        val art = compose.onNode(hasTestTag("tvArtwork") and hasAnyAncestor(hasTestTag("titleHeader")), useUnmergedTree = true)
        assertEquals("the picture from the screen's top", 0f, art.getUnclippedBoundsInRoot().top.value, 0.5f)
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
        show(TopLevel.HOME) { TvHomeScreen(HomeUiState(playlistName = "Example Playlist", hero = listOf(featured), hasVod = true), HomeActions(), autoAdvance = false) }
        val art = compose.onNode(hasTestTag("tvArtwork") and hasAnyAncestor(hasTestTag("heroFrame")), useUnmergedTree = true)
        assertEquals(TvArtworkAlignment, art.fetchSemanticsNode().config.getOrNull(ArtworkAlignment))
    }

    @Test
    fun homeShowsWhenThePlaylistWasUpdated() {
        val featured = HeroPage(movies[0], meta = "Movie · 2024", inMyList = false, resumable = false)
        val state = HomeUiState(playlistName = playlist.name, updatedAt = playlist.updatedAt, now = NOW, hero = listOf(featured), newMovies = movies, hasVod = true)
        show(TopLevel.HOME) { TvHomeScreen(state, HomeActions(), autoAdvance = false) }
        compose.onNodeWithTag("lastUpdate").assertIsDisplayed()
            .assert(hasText("Updated", substring = true)).assert(hasText(playlist.name, substring = true))
        assertTrue("above the carousel", bounds("lastUpdate").bottom <= bounds("heroFrame").top + 0.5.dp)
    }

    @Test
    fun theMoviesTabShowsWhenThePlaylistWasUpdatedClearOfTheHeadersWords() {
        moviesTab(VodUiState(title = "Movies", categories = listOf(drama), rows = listOf(CategoryRow(drama, movies))))
        focusFirstCard("shelf:m-drama")
        compose.onNodeWithTag("lastUpdate").assertIsDisplayed()
            .assert(hasText("Updated", substring = true)).assert(hasText(playlist.name, substring = true))
        val note = bounds("lastUpdate")
        val words = compose.onNode(hasTestTag("headerText") and hasAnyAncestor(hasTestTag("titleHeader")), useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("the line ends before Search", note.right <= bounds("tabSearch").left + 0.5.dp)
        assertTrue("the header's words start below the bar's line", words.top >= note.bottom - 0.5.dp)
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
     * The categories in the top bar: Up from the first row reaches the tab, Right along the bar the Categories pill; OK
     * opens the picker on All, and a category chosen there is the one the tab opens.
     */
    @Test
    fun upThenRightReachesTheCategoriesPillAndItsPicker() {
        var chosen: MediaCategory? = null
        show(TopLevel.MOVIES, onTab = true) { VodScreen(moviesPath, VodActions(onCategory = { chosen = it })) }
        press(Key.DirectionDown)
        advance(500)
        assertTrue(focusedIn("shelfResume"))
        press(Key.DirectionUp)
        advance(300)
        assertTrue("Up from the first row lands on the tab", compose.onNodeWithTag("tabMovies").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true)
        var presses = 0
        while (compose.onAllNodes(isFocused() and hasTestTag("categoryFilter")).fetchSemanticsNodes().isEmpty()) {
            check(presses++ < 8) { "Right along the bar never reached the Categories pill" }
            press(Key.DirectionRight)
            advance(50)
        }
        assertEquals("the pill reads Categories", listOf("Categories"), compose.onAllNodesWithTag("categoryFilterLabel", useUnmergedTree = true).fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString() })
        assertTrue("the pill sits before Search", bounds("categoryFilter").right <= bounds("tabSearch").left + 0.5.dp)

        press(Key.DirectionCenter)
        advance(500)
        assertTrue("the picker opens on All", compose.onAllNodes(isFocused() and hasTestTag("categoryAll")).fetchSemanticsNodes().isNotEmpty())
        presses = 0
        while (compose.onAllNodes(isFocused() and hasTestTag("category:m-comedy")).fetchSemanticsNodes().isEmpty()) {
            check(presses++ < 6) { "Down along the picker never reached Comedy" }
            press(Key.DirectionDown)
            advance(50)
        }
        press(Key.DirectionCenter)
        advance(500)
        assertEquals(comedy, chosen)
        assertTrue("the picker closed", compose.onAllNodesWithTag("categoryPicker").fetchSemanticsNodes().isEmpty())
    }

    /** A category's titles: the pill names it, and its picker opens on it. */
    @Test
    fun thePillNamesTheCategoryShown() {
        show(TopLevel.MOVIES) {
            TvTopBarCategories(TvCategoryChoice(listOf(drama, comedy), locked = emptySet(), selected = comedy, onChoose = {}, onManage = {}))
        }
        assertEquals(listOf("Comedy"), compose.onAllNodesWithTag("categoryFilterLabel", useUnmergedTree = true).fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString() })
        compose.onNodeWithTag("categoryFilter").requestFocus()
        press(Key.DirectionCenter)
        advance(500)
        assertTrue("the picker opens on the category shown", compose.onAllNodes(isFocused() and hasTestTag("category:m-comedy")).fetchSemanticsNodes().isNotEmpty())
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
        playlistName = playlist.name,
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
