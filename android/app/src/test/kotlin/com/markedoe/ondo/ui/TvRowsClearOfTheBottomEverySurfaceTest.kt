package com.markedoe.ondo.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.library.RowOrder
import com.markedoe.ondo.core.library.Selections
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
import com.markedoe.ondo.ui.screens.BrowseKind
import com.markedoe.ondo.ui.screens.BrowseScreen
import com.markedoe.ondo.ui.screens.BrowseUiState
import com.markedoe.ondo.ui.screens.CategoryRow
import com.markedoe.ondo.ui.screens.DetailActions
import com.markedoe.ondo.ui.screens.HomeActions
import com.markedoe.ondo.ui.screens.HomeUiState
import com.markedoe.ondo.ui.screens.MyListActions
import com.markedoe.ondo.ui.screens.MyListKind
import com.markedoe.ondo.ui.screens.MyListScreen
import com.markedoe.ondo.ui.screens.MyListUiState
import com.markedoe.ondo.ui.screens.PersonScreen
import com.markedoe.ondo.ui.screens.PersonUiState
import com.markedoe.ondo.ui.screens.PosterGridScreen
import com.markedoe.ondo.ui.screens.SearchUiState
import com.markedoe.ondo.ui.screens.VodActions
import com.markedoe.ondo.ui.screens.VodScreen
import com.markedoe.ondo.ui.screens.VodUiState
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.CARD_FOCUS_SPILL
import com.markedoe.ondo.ui.tv.FocusEntry
import com.markedoe.ondo.ui.tv.LocalFocusMemory
import com.markedoe.ondo.ui.tv.LocalTitleHeaders
import com.markedoe.ondo.ui.tv.LocalTvTopBar
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TitleHeaders
import com.markedoe.ondo.ui.tv.TvHomeScreen
import com.markedoe.ondo.ui.tv.TvMovieDetailScreen
import com.markedoe.ondo.ui.tv.TvNavigationFrame
import com.markedoe.ondo.ui.tv.TvSearchScreen
import com.markedoe.ondo.ui.tv.TvSeriesDetailScreen
import com.markedoe.ondo.ui.tv.TvTokens
import com.markedoe.ondo.ui.tv.TvTopBar
import com.markedoe.ondo.ui.tv.rememberFocusMemory
import com.markedoe.ondo.ui.tv.tvSafeArea
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * On a 960 × 540 dp TV, the focused card of a row (or of a grid) is drawn whole at least the 5 % a TV may crop
 * ([TvTokens.EdgeTop], 27 dp) above the screen's bottom, grown by its focus, wherever the remote brings it: Down from
 * Home's featured titles into its first row, whatever kind of row comes first, then Down along the rows; Down along the
 * Movies and Series tabs, a category's and a genre's grids, My List, a person's page, Search and a title's page (More
 * like this, a season's episodes). Every source file that shows poster rows on a TV is listed in [surfaces]: a new one
 * is added there, with its walk here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvRowsClearOfTheBottomEverySurfaceTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After
    fun forget() = UiMappers.forgetCatalogue()

    private fun movie(id: String, name: String, category: String = "m-drama") = ShowcaseItem.MovieItem(
        Movie(id = id, name = name, categoryId = category, streamUrl = "https://example.com/movie/$id.mp4", rating = 7.0, year = 2024, plot = "A keeper counts the ships that never come.", addedAt = 1_700_000_000L),
    )

    private fun series(id: String, name: String, category: String = "s-drama") =
        ShowcaseItem.SeriesItem(Series(id = id, name = name, categoryId = category, plot = "A keeper counts the ships that never come.", rating = 7.0, year = 2024))

    private val movies = (1..8).map { movie("$it", "Example Film $it") }
    private val shows = (1..8).map { series("s$it", "Example Series $it") }
    private val resumeMovies = movies.take(4).map { ResumeItem("resume:${it.key}", it.title, subtitle = "2024", imageUrl = null, progress = 0.4f, minutesLeft = 7, hasPage = true, showcase = it) }
    private val resumeSeries = shows.take(4).map { ResumeItem("resume:${it.key}", it.title, subtitle = "S1 E2", imageUrl = null, progress = 0.4f, minutesLeft = 7, isSeries = true, hasPage = true, showcase = it) }
    private val channels = (1..6).map {
        ChannelRow(Channel(id = "c$it", name = "Example Channel $it", categoryId = "news", streamUrl = "https://example.com/live/c$it.m3u8"), nowTitle = "Morning News", nowProgress = 0.3f, nextTitle = null)
    }
    private val selection = SelectionRow("pick", "Example selection", listOf(Selections.decade(2020)), emptyMap(), movies.drop(2) + shows.take(3))
    private val selectionKey = Selections.rowKey(selection.id)
    private val hero = listOf(HeroPage(movies[0], meta = "Movie · 2024", inMyList = false, resumable = false, plot = "A keeper counts the ships that never come."))

    /** Every kind of row Home shows, its shelf's tag. */
    private val kinds = mapOf(
        RowOrder.HOME_CONTINUE_MOVIES to "shelfResumeMovies",
        RowOrder.HOME_CONTINUE_SERIES to "shelfResumeSeries",
        RowOrder.MY_LIST to "shelfMyList",
        RowOrder.HOME_CHANNELS to "shelfChannels",
        RowOrder.HOME_NEW_MOVIES to "shelfNewMovies",
        RowOrder.HOME_NEW_SERIES to "shelfNewSeries",
        RowOrder.TRENDING to "shelfTrending",
        selectionKey to "shelfSelection-${selection.id}",
    )

    /** Home with every row, [first] at the top, then the others in their usual order. */
    private fun home(first: String) = HomeUiState(
        playlistName = "Example Playlist",
        hero = hero,
        continueWatching = resumeMovies + resumeSeries,
        myList = movies.reversed(),
        newMovies = movies,
        newSeries = shows,
        trending = movies.drop(1),
        hasVod = true,
        order = listOf(first) + kinds.keys.filter { it != first },
        selections = mapOf(selectionKey to selection),
        channels = channels,
    )

    private object Headers : TitleHeaders {
        override fun header(item: ShowcaseItem): Flow<TitleHeader> = flowOf(TitleHeader(item, genres = listOf("Drama"), tmdbRating = "7.2", plot = "A keeper counts the ships that never come."))

        override suspend fun load(item: ShowcaseItem) = Unit
    }

    /** How a page keeps clear of the screen's edges in the app (`TvMargins` in `OndoApp`). */
    private enum class Margins { NONE, TAB, SAFE_AREA }

    /**
     * [content] as the app shows it on a TV: under the top bar for a tab ([tab], [tabPage]) or a category's page
     * ([bar] without [tabPage]), with the page's margins, a focus memory, and the focus entering the page.
     */
    private fun show(tab: TopLevel = TopLevel.HOME, bar: Boolean = true, tabPage: Boolean = bar, margins: Margins = Margins.NONE, onTab: Boolean = false, content: @Composable () -> Unit) {
        var inputModes: InputModeManager? = null
        var topBar: TvTopBar? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme {
                TelevisionFrame {
                    TvNavigationFrame(tab, showBar = bar, tabPage = tabPage, onSelect = {}, onSearch = {}, onSettings = {}) {
                        topBar = LocalTvTopBar.current
                        val modifier = when (margins) {
                            Margins.NONE -> Modifier
                            Margins.TAB -> Modifier.tvSafeArea(tab = true)
                            Margins.SAFE_AREA -> Modifier.tvSafeArea()
                        }
                        CompositionLocalProvider(LocalFocusMemory provides rememberFocusMemory(), LocalTitleHeaders provides Headers) {
                            FocusEntry(modifier) { content() }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        if (onTab) {
            compose.runOnIdle { topBar?.browsing = true }
            compose.onNodeWithTag(tab.tag).requestFocus()
        }
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.mainClock.autoAdvance = false
        advance(1_000)
    }

    private fun advance(ms: Long) {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onAllNodes(isFocused()).onLast().performKeyInput { pressKey(key) }
        advance(1_000)
    }

    private fun focusedIn(rows: SemanticsMatcher) = compose.onAllNodes(isFocused() and rows).fetchSemanticsNodes().isNotEmpty()

    private fun within(tag: String) = hasAnyAncestor(hasTestTag(tag))

    /** A poster, named for TalkBack by its title (not a button or a field of the page). */
    private val poster = SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription) and hasClickAction()

    private fun posters(tag: String) = within(tag) and poster

    /** The screen's bottom in the root's pixels, less the 5 % a TV may crop. */
    private fun safeBottom(): Float = with(compose.density) { (540.dp - TvTokens.EdgeTop).toPx() }

    /**
     * Where the focused [node] is drawn at most, in the root's pixels, grown by its focus: its bounds with its scale (a
     * TV card), or its place and the frame drawn around it ([com.markedoe.ondo.ui.tv.focusFrame], a phone's card).
     */
    private fun grownBottom(node: SemanticsNode): Float {
        val coordinates = node.layoutInfo.coordinates
        val drawn = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = false)
        val scaled = drawn.height > node.size.height * 1.03f
        return if (scaled) drawn.bottom else drawn.bottom + node.size.height * CARD_FOCUS_SPILL
    }

    /** The focused card in [rows] ends, grown, at least the TV's safe margin above the screen's bottom. */
    private fun assertClearOfTheBottom(rows: SemanticsMatcher, step: String) {
        val card = compose.onAllNodes(isFocused() and rows).onLast().fetchSemanticsNode()
        val name = card.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
            ?: card.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
        val bottom = grownBottom(card)
        val limit = safeBottom()
        assertTrue("$step: $name ends at ${bottom / compose.density.density} dp, past ${limit / compose.density.density} dp", bottom <= limit + 0.5f)
        val top = card.boundsInRoot.top
        assertTrue("$step: $name starts at ${top / compose.density.density} dp, above the screen", top >= 0f)
    }

    /**
     * From where the focus is, Down [presses] times; each time the focus is in [rows] (and once at least), its card is
     * clear of the screen's bottom.
     */
    private fun walkDown(rows: SemanticsMatcher, presses: Int, surface: String) {
        var checked = 0
        if (focusedIn(rows)) {
            assertClearOfTheBottom(rows, "$surface, on arrival")
            checked++
        }
        repeat(presses) { step ->
            press(Key.DirectionDown)
            if (focusedIn(rows)) {
                assertClearOfTheBottom(rows, "$surface, Down ${step + 1}")
                checked++
            }
        }
        assertTrue("$surface: the remote reached its rows", checked > 0)
    }

    /** The poster of [title] in [rows] given the focus, as the remote coming back to it would. */
    private fun focusOn(title: String, rows: SemanticsMatcher) {
        compose.onAllNodes(hasContentDescription(title) and rows).onFirst().requestFocus()
        advance(1_000)
    }

    @Test
    fun everyKindOfHomeRowIsListed() {
        assertEquals(RowOrder.homeBuiltIns(hasVod = true, collections = false).toSet() + selectionKey, kinds.keys)
    }

    /**
     * Home: Down from Play into the first row, whatever its kind, its name whole below the frame and its card clear of
     * the bottom, then Down along every row.
     */
    @Test
    fun homeFromTheFeaturedTitlesDownEveryRow() {
        for ((key, shelf) in kinds) {
            show { TvHomeScreen(home(key), HomeActions(), autoAdvance = false) }
            compose.onNodeWithTag("heroPlay").requestFocus()
            advance(1_000)
            press(Key.DirectionDown)
            assertTrue("$key: Down from Play lands in $shelf", focusedIn(within(shelf)))
            val frame = compose.onNodeWithTag("hero", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val row = compose.onNodeWithTag(shelf, useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue("$key: the row's name whole below the frame (${row.top} under ${frame.bottom})", row.top >= frame.bottom - 0.5.dp)
            walkDown(within("homeRows"), presses = kinds.size, surface = "Home, $key first")
        }
    }

    /** The smaller frame still holds the focused poster's title, facts and two lines of synopsis whole. */
    @Test
    fun homesSmallerFrameHoldsItsWordsWhole() {
        show { TvHomeScreen(home(RowOrder.HOME_NEW_MOVIES), HomeActions(), autoAdvance = false) }
        compose.onNodeWithTag("heroPlay").requestFocus()
        advance(1_000)
        press(Key.DirectionDown)
        val frame = compose.onNodeWithTag("heroFrame", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals("the smaller frame's height", 146f, frame.bottom.value - frame.top.value, 0.5f)
        val words = compose.onNodeWithTag("headerText", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("the words inside the frame: $words in $frame", words.top >= frame.top - 0.5.dp && words.bottom <= frame.bottom + 0.5.dp)
    }

    private val categories = (1..4).map { MediaCategory("m-c$it", "Example Category $it", ContentType.MOVIE) }

    @Test
    fun theMoviesTabDownEveryRow() {
        val state = VodUiState(
            title = "Movies",
            categories = categories,
            continueWatching = resumeMovies,
            rows = categories.map { category -> CategoryRow(category, (1..8).map { movie("${category.id}-$it", "${category.name} Film $it", category.id) }) },
        )
        show(TopLevel.MOVIES, onTab = true) { VodScreen(state, VodActions()) }
        walkDown(posters("vod"), presses = categories.size + 2, surface = "the Movies tab")
    }

    @Test
    fun theSeriesTabDownEveryRow() {
        val seriesCategories = (1..4).map { MediaCategory("s-c$it", "Example Category $it", ContentType.SERIES) }
        val state = VodUiState(
            title = "Series",
            categories = seriesCategories,
            continueWatching = resumeSeries,
            rows = seriesCategories.map { category -> CategoryRow(category, (1..8).map { series("${category.id}-$it", "${category.name} Series $it", category.id) }) },
        )
        show(TopLevel.SERIES, onTab = true) { VodScreen(state, VodActions()) }
        walkDown(posters("vod"), presses = seriesCategories.size + 2, surface = "the Series tab")
    }

    private val longGrid = (0 until 40).map { movie("g$it", "Grid Film $it") }

    @Test
    fun aCategorysGridDownEveryRow() {
        show(TopLevel.MOVIES, tabPage = false, onTab = true) { PosterGridScreen("Drama", longGrid, filter = "", onFilter = {}, onOpen = {}, onBack = {}) }
        walkDown(posters("grid"), presses = 8, surface = "a category's grid")
    }

    @Test
    fun aGenresTitlesDownEveryRow() {
        show(TopLevel.HOME, tabPage = false, onTab = true) { BrowseScreen(BrowseUiState(BrowseKind.GENRE, "Mystery", movies = longGrid), onOpen = {}, onFestival = {}, onBack = {}) }
        walkDown(posters("browse"), presses = 8, surface = "a genre's titles")
    }

    @Test
    fun myListDownEveryRow() {
        show(TopLevel.MY_LIST, margins = Margins.TAB) { MyListScreen(MyListUiState(movies = longGrid), MyListKind.MOVIES, MyListActions()) }
        focusOn("Grid Film 0", posters("myList"))
        walkDown(posters("myList"), presses = 8, surface = "My List")
    }

    @Test
    fun aPersonsPageDownEveryRow() {
        val state = PersonUiState("Ann Example", movies = longGrid.take(16), series = shows, searchedOnline = true)
        show(bar = false, margins = Margins.SAFE_AREA) { PersonScreen(state, onOpen = {}, onBack = {}) }
        focusOn("Grid Film 0", posters("person"))
        walkDown(posters("person"), presses = 8, surface = "a person's page")
    }

    @Test
    fun searchDownEveryRow() {
        val state = SearchUiState(query = "example", movies = movies, series = shows, channels = channels)
        show(bar = false, margins = Margins.SAFE_AREA) { TvSearchScreen(state, onQuery = {}, onOpen = {}, onPlayChannel = {}, onFilters = {}, onPerson = {}) }
        focusOn("Example Film 1", posters("search"))
        walkDown(posters("search"), presses = 4, surface = "Search")
    }

    @Test
    fun aMoviesPageDownToMoreLikeThis() {
        val state = UiMappers.movieDetail(Fixtures.state(), Fixtures.glassCity).copy(similar = movies)
        show(bar = false) { TvMovieDetailScreen(state, DetailActions()) }
        walkDown(posters("similar"), presses = 10, surface = "a movie's page")
    }

    @Test
    fun aSeriesPageDownToItsEpisodes() {
        val state = UiMappers.seriesDetail(Fixtures.state(), Fixtures.harborLights, null, null)
        val episodeCard = SemanticsMatcher("an episode's card") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("episodeCard-") == true }
        show(bar = false) { TvSeriesDetailScreen(state, DetailActions()) }
        walkDown(episodeCard, presses = 10, surface = "a series' page")
    }

    companion object {
        /**
         * Every source file that shows poster rows or grids on a TV, and the walk above that covers it. A new one is
         * added here, with its walk.
         */
        val surfaces = mapOf(
            "ui/screens/BrowseScreen.kt" to "aGenresTitlesDownEveryRow",
            "ui/screens/CollectionScreens.kt" to "a collection's grid: TvScrolling, as My List",
            "ui/screens/MyListScreen.kt" to "myListDownEveryRow",
            "ui/screens/PersonScreen.kt" to "aPersonsPageDownEveryRow",
            "ui/screens/VodScreens.kt" to "theMoviesTabDownEveryRow, theSeriesTabDownEveryRow, aCategorysGridDownEveryRow",
            "ui/tv/TvDetailScreens.kt" to "aMoviesPageDownToMoreLikeThis, aSeriesPageDownToItsEpisodes",
            "ui/tv/TvHomeScreen.kt" to "homeFromTheFeaturedTitlesDownEveryRow",
            "ui/tv/TvSearchScreen.kt" to "searchDownEveryRow",
        )

        /** Where the cards and rows are defined, or a phone's own screens (a TV has its own of these). */
        private val notASurface = setOf(
            "ui/components/Shelves.kt",
            "ui/tv/TvDesign.kt",
            "ui/tv/TvFocusHeader.kt",
            "ui/screens/DetailScreens.kt",
            "ui/screens/HomeScreen.kt",
            "ui/screens/SearchScreen.kt",
        )

        private val rows = Regex("""(?<![A-Za-z.])(TvPosterCard|PosterCard|tvShelf|Shelf|TvHeaderAbove)\(""")
    }

    @Test
    fun everySurfaceWithPosterRowsIsWalked() {
        val root = File("src/main/kotlin/com/markedoe/ondo")
        val found = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { file ->
            val source = file.readText()
            rows.findAll(source).any { match -> !source.substring(maxOf(0, match.range.first - 4), match.range.first).endsWith("fun ") }
        }.map { it.relativeTo(root).invariantSeparatorsPath }.filter { it !in notASurface }.toSet()
        assertEquals("a surface with poster rows is added to `surfaces`, with its walk", surfaces.keys, found)
    }
}
