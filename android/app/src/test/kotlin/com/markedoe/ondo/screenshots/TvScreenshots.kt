package com.markedoe.ondo.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.markedoe.ondo.ui.Labels
import com.markedoe.ondo.ui.TopLevel
import com.markedoe.ondo.ui.UiMappers
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.model.TitleHeader
import com.markedoe.ondo.ui.screens.HomeActions
import com.markedoe.ondo.ui.screens.VodActions
import com.markedoe.ondo.ui.screens.VodScreen
import com.markedoe.ondo.ui.screens.VodUiState
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.LocalTitleHeaders
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TitleHeaders
import com.markedoe.ondo.ui.tv.TvHomeScreen
import com.markedoe.ondo.ui.tv.CATEGORIES_TAG
import com.markedoe.ondo.ui.tv.LocalTvTopBar
import com.markedoe.ondo.ui.tv.TvNavigationFrame
import com.markedoe.ondo.ui.tv.TvTopBar
import com.markedoe.ondo.ui.tv.TvCategoryChoice
import com.markedoe.ondo.ui.tv.TvSortChoice
import com.markedoe.ondo.ui.tv.TvTopBarCategories
import com.markedoe.ondo.data.PosterSort
import com.markedoe.ondo.ui.screens.BrowseKind
import com.markedoe.ondo.ui.screens.BrowseScreen
import com.markedoe.ondo.ui.screens.PosterGridScreen
import com.markedoe.ondo.ui.screens.browseTitle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Reference screenshots of a TV's tab pages as the remote leaves them, from the demo catalogue: the header above the
 * Movies tab (its backdrop from the screen's top, no playlist line in the bar), with a card of Continue watching
 * focused and with My List's; the viewer's path on the Movies and Series tabs (the focus on the tab, Down once, Down
 * again), the Categories picker open; a category's titles (the focus on the tab, then Down: no row of their own above
 * the posters, the pill naming the category) and the pill's picker there with the order beside the categories; a
 * genre's titles, the pill naming the genre; and Home with the remote in its rows. Compared by `verifyRoborazziDebug` with
 * [BASELINES]`/tv/`; the store listing has none of them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w960dp-h540dp-land-television-xhdpi")
class TvScreenshots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val catalogue = ShowcaseCatalogue.load(french = false)
    private val state = catalogue.state()
    private val now = ShowcaseCatalogue.NOW
    private val labels = Labels("Movie", "Series", Locale.US)

    /** What the app's view model would show of a title in the header: the demo's details. */
    private val headers = object : TitleHeaders {
        override fun header(item: ShowcaseItem): Flow<TitleHeader> = flowOf(UiMappers.titleHeader(state, item, null, Locale.US))

        override suspend fun load(item: ShowcaseItem) = Unit
    }

    @Before
    fun pictures() = ShowcaseImages.install(ApplicationProvider.getApplicationContext())

    @After
    fun noPictures() = ShowcaseImages.reset()

    /**
     * [content] as [tab]'s page ([tabPage] false: a page beyond the tab, a category's or a genre's titles); [onTab]:
     * opened as the remote moved along the bar, the focus left on the tab.
     */
    private fun show(tab: TopLevel, onTab: Boolean = false, tabPage: Boolean = true, content: @Composable () -> Unit) {
        var inputModes: InputModeManager? = null
        var bar: TvTopBar? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme {
                CompositionLocalProvider(LocalCarouselAutoAdvance provides false) {
                    TelevisionFrame {
                        TvNavigationFrame(tab, showBar = true, tabPage = tabPage, onSelect = {}, onSearch = {}, onSettings = {}) {
                            bar = LocalTvTopBar.current
                            CompositionLocalProvider(LocalTitleHeaders provides headers, content = content)
                        }
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
        advance()
    }

    /** Past the header's wait and its animations. */
    private fun advance() {
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    /** The first card of the first of [shelves] the page has. */
    private fun focusFirstCard(vararg shelves: String) {
        val shelf = shelves.first { compose.onAllNodes(hasTestTag(it)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(shelf))).onFirst().requestFocus()
        advance()
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("$BASELINES/tv/en-US/$name.png", BaselineOptions)

    /** The whole screen, the picker's window over the page included. */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureScreen(name: String) = captureScreenRoboImage("$BASELINES/tv/en-US/$name.png", BaselineOptions)

    private fun moviesTab(onTab: Boolean = false) = show(TopLevel.MOVIES, onTab) { VodScreen(UiMappers.vod(state, series = false, title = "Movies"), VodActions()) }

    private fun press(key: Key) {
        compose.onAllNodes(isFocused()).onLast().performKeyInput { pressKey(key) }
        advance()
    }

    private fun focusedIn(shelf: String) = compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag(shelf))).fetchSemanticsNodes().isNotEmpty()

    /** The rows of [vod], by their tags from the top: Continue watching first, then at least two more. */
    private fun shelves(vod: VodUiState): List<String> {
        val tags = listOfNotNull(
            "shelfResume".takeIf { vod.continueWatching.isNotEmpty() },
            "shelfMyList".takeIf { vod.myList.isNotEmpty() },
            "shelfAwards".takeIf { vod.awardWinners.isNotEmpty() },
        ) + vod.rows.map { "shelf:${it.category.id}" }
        assertTrue("Continue watching, then at least two more rows: $tags", tags.size >= 3 && tags.first() == "shelfResume")
        return tags
    }

    /** The viewer's path on the Movies or the Series tab: on the tab, Down once, Down again, each captured as [name]`-…`. */
    private fun downPath(name: String, series: Boolean) {
        val vod = UiMappers.vod(state, series = series, title = if (series) "Series" else "Movies")
        val rows = shelves(vod)
        show(if (series) TopLevel.SERIES else TopLevel.MOVIES, onTab = true) { VodScreen(vod, VodActions()) }
        capture("$name-tab-focused")
        press(Key.DirectionDown)
        assertTrue("Down from the tab: the first row", focusedIn(rows[0]))
        capture("$name-down-1")
        press(Key.DirectionDown)
        assertTrue("Down again: the next row", focusedIn(rows[1]))
        capture("$name-down-2")
    }

    @Test
    fun moviesWithContinueWatchingFocused() {
        moviesTab()
        focusFirstCard("shelfResume")
        capture("movies-continue-watching")
    }

    @Test
    fun moviesWithMyListFocused() {
        moviesTab()
        // My List is below Continue watching, off the screen: the remote goes down to it.
        focusFirstCard("shelfResume")
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
        advance()
        assertTrue("a card of My List has the focus", compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag("shelfMyList"))).fetchSemanticsNodes().isNotEmpty())
        capture("movies-my-list")
    }

    @Test
    fun moviesDownFromTheTab() = downPath("movies", series = false)

    @Test
    fun seriesDownFromTheTab() = downPath("series", series = true)

    @Test
    fun theCategoriesPickerOpen() {
        moviesTab(onTab = true)
        var presses = 0
        while (compose.onAllNodes(isFocused() and hasTestTag(CATEGORIES_TAG)).fetchSemanticsNodes().isEmpty()) {
            check(presses++ < 8) { "Right along the bar never reached the Categories pill" }
            press(Key.DirectionRight)
        }
        press(Key.DirectionCenter)
        assertTrue("the picker has the focus", compose.onAllNodes(isFocused() and hasTestTag("categoryAll")).fetchSemanticsNodes().isNotEmpty())
        captureScreen("categories-picker-open")
    }

    /** The demo's movie category with the most titles: the fullest grid. */
    private val category = state.content.movieCategories.maxBy { c -> state.content.movies.count { it.categoryId == c.id } }

    /** A category's titles as the app shows them on a TV: the pill names the category and holds the order. */
    private fun categoryGrid() = show(TopLevel.MOVIES, onTab = true, tabPage = false) {
        var sort by remember { mutableStateOf(PosterSort.PLAYLIST) }
        val vod = remember { UiMappers.vod(state, series = false, title = "Movies") }
        val order = TvSortChoice(sort, { sort = it }, hideWatched = state.settings.hideWatchedMovies, onHideWatched = {})
        TvTopBarCategories(TvCategoryChoice(vod.categories, vod.lockedCategoryIds, selected = category, onChoose = {}, onManage = {}, sort = order))
        val items = remember(sort) { UiMappers.grid(state, series = false, categoryId = category.id, myList = false, filter = "", sort = sort) }
        PosterGridScreen(category.name, items, filter = "", onFilter = {}, onOpen = {}, onBack = {}, sort = sort, onSort = { sort = it })
    }

    @Test
    fun aCategorysTitlesFromTheTabThenDown() {
        categoryGrid()
        capture("grid-category")
        press(Key.DirectionDown)
        assertTrue("Down from the tab: the posters", focusedIn("grid"))
        capture("grid-down-1")
    }

    @Test
    fun thePickerWithTheOrderBesideTheCategories() {
        categoryGrid()
        var presses = 0
        while (compose.onAllNodes(isFocused() and hasTestTag(CATEGORIES_TAG)).fetchSemanticsNodes().isEmpty()) {
            check(presses++ < 8) { "Right along the bar never reached the Categories pill" }
            press(Key.DirectionRight)
        }
        press(Key.DirectionCenter)
        assertTrue("the picker has the order", compose.onAllNodes(hasTestTag("sort-rating")).fetchSemanticsNodes().isNotEmpty())
        captureScreen("picker-with-sort")
    }

    /** The demo's most common movie genre: a full page of titles. */
    private val genre = state.movieDetails.values.flatMap { details -> details.genre.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() } }
        .groupingBy { it }.eachCount().maxBy { it.value }.key

    @Test
    fun aGenresTitles() {
        show(TopLevel.HOME, tabPage = false) {
            var sort by remember { mutableStateOf(PosterSort.PLAYLIST) }
            val page = remember(sort) { UiMappers.browse(state, BrowseKind.GENRE, genre, Locale.US, sort = sort) }
            TvTopBarCategories(TvCategoryChoice.page(browseTitle(page), TvSortChoice(sort, { sort = it })))
            BrowseScreen(page, onOpen = {}, onFestival = {}, onBack = {}, sort = sort, onSort = { sort = it })
        }
        focusFirstCard("browse")
        capture("browse-genre")
    }

    @Test
    fun homeWithTheRowsFocused() {
        show(TopLevel.HOME) { TvHomeScreen(UiMappers.home(state, labels, now).copy(channels = emptyList()), HomeActions(), autoAdvance = false) }
        focusFirstCard("shelfResumeMovies", "shelfResumeSeries", "shelfMyList", "shelfNewMovies")
        capture("home-rows")
    }
}
