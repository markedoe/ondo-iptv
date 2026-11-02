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
import com.markedoe.ondo.ui.tv.TvNavigationFrame
import com.markedoe.ondo.ui.tv.VisibleRowsBottom
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
 * the chips focused, no card cut by the screen's bottom; a focused card, grown, whole within its row at either end in
 * both directions; and a continue watching card's lines clear of its focus frame.
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

    /** [content] as a tab page on a TV: under the top bar, which shows [playlist] unless the page says otherwise. */
    private fun show(tab: TopLevel, direction: LayoutDirection = LayoutDirection.Ltr, content: @Composable () -> Unit) {
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme {
                TelevisionFrame {
                    TvNavigationFrame(tab, showBar = true, onSelect = {}, onSearch = {}, onSettings = {}, playlist = playlist) {
                        CompositionLocalProvider(LocalTitleHeaders provides Headers, LocalLayoutDirection provides direction, content = content)
                    }
                }
            }
        }
        compose.waitForIdle()
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

    /** Where the Movies tab's list stops drawing its rows, from the screen's top. */
    private fun visibleRowsBottom(): Dp {
        val list = compose.onNodeWithTag("vod").fetchSemanticsNode()
        val pixels = list.config.getOrNull(VisibleRowsBottom)?.invoke() ?: error("the rows' list says where it stops drawing")
        return bounds("vod").top + with(compose.density) { pixels.toDp() }
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
        assertWithin("$shelf's drawn part", bounds(shelf), list.top, visibleRowsBottom())
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

    /** The chips focused: the first row shows whole below them, and no row is cut by the screen's bottom. */
    @Test
    fun withTheChipsFocusedNoCardIsCutAtTheBottom() {
        val shelves = listOf("shelfResume", "shelfMyList", "shelf:m-drama")
        moviesTab(VodUiState(title = "Movies", categories = listOf(drama), continueWatching = resume, myList = movies, rows = listOf(CategoryRow(drama, movies))))
        compose.onNodeWithTag("categoryFilter").requestFocus()
        advance(1_000)
        val list = bounds("vod")
        val drawn = visibleRowsBottom()
        assertTrue("the list draws within itself", drawn <= list.bottom + 0.5.dp)
        assertWithin("the first row", bounds("shelfResume"), list.top, drawn)
        for (shelf in shelves) {
            if (compose.onAllNodesWithTag(shelf, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) continue
            val row = bounds(shelf)
            assertTrue("$shelf is cut at the bottom: $row, drawn to $drawn", row.bottom <= drawn + 0.5.dp || row.top >= drawn - 0.5.dp)
        }

        // Down to the first row: the header back, the row whole below it.
        compose.onNodeWithTag("categoryFilter").performKeyInput { pressKey(Key.DirectionDown) }
        advance(1_500)
        assertTrue("a card of the first row has the focus", compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag("shelfResume"))).fetchSemanticsNodes().isNotEmpty())
        assertEquals("the list right below the header", bounds("titleHeader").bottom.value, bounds("vod").top.value, 0.5f)
        assertWithin("the first row", bounds("shelfResume"), bounds("vod").top, bounds("vod").bottom)
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
