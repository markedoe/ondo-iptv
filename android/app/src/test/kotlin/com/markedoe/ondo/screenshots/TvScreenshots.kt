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
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.markedoe.ondo.core.TextNormalizer
import com.markedoe.ondo.ui.Labels
import com.markedoe.ondo.ui.TopLevel
import com.markedoe.ondo.ui.UiMappers
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.model.TitleHeader
import com.markedoe.ondo.ui.screens.HomeActions
import com.markedoe.ondo.ui.screens.VodActions
import com.markedoe.ondo.ui.screens.VodScreen
import com.markedoe.ondo.ui.screens.RowOrderActions
import com.markedoe.ondo.ui.tv.TvRowOrderScreen
import com.markedoe.ondo.core.library.RowOrder
import com.markedoe.ondo.ui.screens.VodUiState
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.LocalTitleHeaders
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TitleHeaders
import com.markedoe.ondo.ui.tv.TvHomeScreen
import com.markedoe.ondo.ui.tv.TvMovieDetailScreen
import com.markedoe.ondo.ui.screens.DetailActions
import com.markedoe.ondo.ui.tv.FocusEntry
import com.markedoe.ondo.ui.tv.LocalFocusMemory
import com.markedoe.ondo.ui.tv.LocalTvTopBar
import com.markedoe.ondo.ui.tv.SORT_CHIP_TAG
import com.markedoe.ondo.ui.tv.TvNavigationFrame
import com.markedoe.ondo.ui.tv.TvTopBar
import com.markedoe.ondo.ui.tv.rememberFocusMemory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.markedoe.ondo.data.PosterSort
import com.markedoe.ondo.ui.screens.BrowseKind
import com.markedoe.ondo.ui.screens.BrowseScreen
import com.markedoe.ondo.ui.screens.PosterGridScreen
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
 * again); a category's titles (the focus on the tab, then Down: no row of their own above the posters, the category's
 * name above them) and its sort chips at the end of that line, reached with Up from the first row; the rows again after
 * Back from a category's titles, the focus on the See all card it was opened from; a genre's titles, named above them;
 * deep in a long grid, the focused row at the top, whole; and Home with the remote in its rows. Compared by `verifyRoborazziDebug` with
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

    /** What the app's view model would show of a title in the header: the demo's details (a copy's, its own title's). */
    private val headers = object : TitleHeaders {
        override fun header(item: ShowcaseItem): Flow<TitleHeader> = flowOf(UiMappers.titleHeader(state, original(item), null, Locale.US).copy(item = item))

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

    private fun moviesTab(onTab: Boolean = false) = show(TopLevel.MOVIES, onTab) { VodScreen(UiMappers.vod(state, series = false, title = "Movies"), VodActions()) }

    private fun press(key: Key) {
        compose.onAllNodes(isFocused()).onLast().performKeyInput { pressKey(key) }
        advance()
    }

    private fun focusedIn(shelf: String) = compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag(shelf))).fetchSemanticsNodes().isNotEmpty()

    /** The rows of [vod], by their tags from the top: All movies (or series) first, then at least two more. */
    private fun shelves(vod: VodUiState): List<String> {
        val tags = vod.shownRows.map { key ->
            when (key) {
                RowOrder.ALL -> "shelfAll"
                RowOrder.CONTINUE_WATCHING -> "shelfResume"
                RowOrder.MY_LIST -> "shelfMyList"
                RowOrder.NEW_RELEASES -> "shelfNew"
                RowOrder.TRENDING -> "shelfTrending"
                RowOrder.AWARD_WINNERS -> "shelfAwards"
                else -> "shelf:" + vod.rows.first { it.key == key }.category.id
            }
        }
        assertTrue("All, then at least two more rows: $tags", tags.size >= 3 && tags.first() == "shelfAll")
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

    /** All movies at the top of the tab: the latest additions, its count in its name, then See all. */
    @Test
    fun moviesWithAllMoviesFocused() {
        moviesTab()
        focusFirstCard("shelfAll")
        capture("movies-all")
    }

    /** Settings → Row order with a row picked up by the remote and moved down once: it shows in the accent colour. */
    @Test
    fun rowOrderWithARowPickedUp() {
        var rows by mutableStateOf(UiMappers.rowOrder(state))
        val actions = RowOrderActions(onSave = { series, arranged, hidden ->
            val byKey = (if (series) rows.series else rows.movies).associateBy { it.key }
            val next = arranged.map { byKey.getValue(it).copy(hidden = it in hidden) }
            rows = if (series) rows.copy(series = next) else rows.copy(movies = next)
        })
        // A page of Settings: no top bar above it.
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme { TelevisionFrame { TvRowOrderScreen(rows, actions) } }
        }
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.mainClock.autoAdvance = false
        advance()
        compose.onNodeWithTag("row-movies-mylist").requestFocus()
        advance()
        press(Key.DirectionCenter)
        press(Key.DirectionUp)
        capture("row-order")
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

    /** The demo's movie category with the most titles: the fullest grid. */
    private val category = state.content.movieCategories.maxBy { c -> state.content.movies.count { it.categoryId == c.id } }

    /** A category's titles as the app shows them on a TV: its name above the posters, its order and Hide watched as chips. */
    private fun categoryGrid() = show(TopLevel.MOVIES, onTab = true, tabPage = false) {
        var sort by remember { mutableStateOf(PosterSort.PLAYLIST) }
        val items = remember(sort) { UiMappers.grid(state, series = false, categoryId = category.id, myList = false, filter = "", sort = sort) }
        PosterGridScreen(
            category.name, items, filter = "", onFilter = {}, onOpen = {}, onBack = {}, sort = sort, onSort = { sort = it },
            hideWatched = state.settings.hideWatchedMovies, onHideWatched = {},
        )
    }

    @Test
    fun aCategorysTitlesFromTheTabThenDown() {
        categoryGrid()
        capture("grid-category")
        press(Key.DirectionDown)
        assertTrue("Down from the tab: the posters", focusedIn("grid"))
        capture("grid-down-1")
    }

    /** A copy of the demo's [movie] for a long grid ([copy]: which one), its own key, its title's pictures and details. */
    private fun copied(movie: com.markedoe.ondo.core.model.Movie, copy: Int) = ShowcaseItem.MovieItem(movie.copy(id = "${movie.id}$COPY$copy"))

    /** A copy's own title ([copied]); any other title as it is. */
    private fun original(item: ShowcaseItem): ShowcaseItem =
        if (item is ShowcaseItem.MovieItem && COPY in item.movie.id) ShowcaseItem.MovieItem(state.content.movies.first { it.id == item.movie.id.substringBefore(COPY) }) else item

    /** Deep in a long grid (the demo's movies, four times over): Down three times from the first row. */
    @Test
    fun deepInACategorysTitles() {
        val items = (0 until 4).flatMap { copy -> state.content.movies.map { copied(it, copy) } }
        show(TopLevel.MOVIES, onTab = true, tabPage = false) {
            PosterGridScreen(category.name, items, filter = "", onFilter = {}, onOpen = {}, onBack = {})
        }
        press(Key.DirectionDown)
        repeat(3) { press(Key.DirectionDown) }
        assertTrue("deep in the posters", focusedIn("grid"))
        capture("grid-down-3")
    }

    /** A category's titles, Down onto the first poster, then Up: the Sort chip at the end of the category's name line. */
    @Test
    fun aCategorysSortChips() {
        categoryGrid()
        press(Key.DirectionDown)
        assertTrue("Down from the tab: the posters", focusedIn("grid"))
        press(Key.DirectionUp)
        assertTrue("Up from the first row: the Sort chip", compose.onAllNodes(isFocused() and hasTestTag(SORT_CHIP_TAG)).fetchSemanticsNodes().isNotEmpty())
        capture("grid-sort-chips")
    }

    /**
     * The Movies tab's rows, a category's titles opened from its row's See all card, then Back: the rows as they were
     * left, the focus on that See all card.
     */
    @Test
    fun theRowsAfterBackFromACategory() {
        val vod = UiMappers.vod(state, series = false, title = "Movies")
        val row = vod.rows[1]
        show(TopLevel.MOVIES, tabPage = false) {
            val nav = rememberNavController()
            NavHost(nav, startDestination = "rows") {
                composable("rows") {
                    CompositionLocalProvider(LocalFocusMemory provides rememberFocusMemory()) {
                        FocusEntry { VodScreen(vod, VodActions(onCategory = { nav.navigate("grid") })) }
                    }
                }
                composable("grid") {
                    CompositionLocalProvider(LocalFocusMemory provides rememberFocusMemory()) {
                        FocusEntry {
                            val items = remember { UiMappers.grid(state, series = false, categoryId = row.category.id, myList = false, filter = "") }
                            PosterGridScreen(row.category.name, items, filter = "", onFilter = {}, onOpen = {}, onBack = {})
                        }
                    }
                }
            }
        }
        val shelf = "shelf:${row.category.id}"
        var presses = 0
        while (!focusedIn(shelf)) {
            check(presses++ < 12) { "Down never reached $shelf" }
            press(Key.DirectionDown)
        }
        presses = 0
        while (!onSeeAll()) {
            check(presses++ < 15) { "Right never reached See all" }
            press(Key.DirectionRight)
        }
        press(Key.DirectionCenter)
        assertTrue("the category's titles", compose.onAllNodes(hasTestTag("grid")).fetchSemanticsNodes().isNotEmpty())
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        advance()
        advance()
        assertTrue("back on the rows", compose.onAllNodes(hasTestTag("grid")).fetchSemanticsNodes().isEmpty())
        assertTrue("back on See all of $shelf", onSeeAll() && focusedIn(shelf))
        capture("rows-after-back")
    }

    private fun onSeeAll() = compose.onAllNodes(isFocused() and hasTestTag("seeAll")).fetchSemanticsNodes().isNotEmpty() || focusedIn("seeAll")

    /** The demo's most common movie genre: a full page of titles. */
    private val genre = state.movieDetails.values.flatMap { details -> details.genre.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() } }
        .groupingBy { it }.eachCount().maxBy { it.value }.key

    @Test
    fun aGenresTitles() {
        show(TopLevel.HOME, tabPage = false) {
            var sort by remember { mutableStateOf(PosterSort.PLAYLIST) }
            val page = remember(sort) { UiMappers.browse(state, BrowseKind.GENRE, genre, Locale.US, sort = sort) }
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

    /**
     * A movie's page in French: the trailer's name whole on its button ("Bande-annonce" above "YouTube"), and the cast
     * with their photos (a demo picture stands for each, from the device, no network).
     */
    @Test
    @Config(qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
    fun aMoviesPageInFrenchWithTheCastsPhotos() {
        val french = ShowcaseCatalogue.load(french = true)
        val movie = french.movie(french.screens.movie)
        val cast = listOf("Nora Quill", "Ana Lima", "José Silva", "Kim Park", "Lena Ward", "Mira Holt")
        val page = UiMappers.movieDetail(french.state(), movie, Locale.FRANCE).let { page ->
            page.copy(
                details = (page.details ?: com.markedoe.ondo.core.model.MediaDetails(title = movie.name)).copy(director = "Sam Reed", cast = cast.joinToString(", ")),
                trailer = "aaaaaaaaaaa",
                faces = (cast + "Sam Reed").associate { TextNormalizer.normalize(it) to "https://example.com/m-sintel-poster.jpg" },
            )
        }
        compose.setContent {
            OndoTheme {
                CompositionLocalProvider(LocalCarouselAutoAdvance provides false) {
                    TelevisionFrame {
                        TvNavigationFrame(TopLevel.MOVIES, showBar = false, onSelect = {}, onSearch = {}, onSettings = {}) { TvMovieDetailScreen(page, DetailActions()) }
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        advance()
        compose.onRoot().captureRoboImage("$BASELINES/tv/fr-FR/movie-trailer-label.png", BaselineOptions)
        compose.onNodeWithTag("detail").performScrollToKey("people")
        advance()
        assertTrue("the cast's photos", compose.onAllNodes(hasTestTag("personPhoto"), useUnmergedTree = true).fetchSemanticsNodes().size >= 4)
        compose.onRoot().captureRoboImage("$BASELINES/tv/fr-FR/movie-cast-photos.png", BaselineOptions)
    }

    private companion object {
        /** Between a demo movie's id and its copy's number ([copied]). */
        const val COPY = "~copy"
    }
}
