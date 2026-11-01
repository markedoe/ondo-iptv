package com.markedoe.ondo.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.ui.components.HeroPage
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.model.TitleHeader
import com.markedoe.ondo.ui.screens.CategoryRow
import com.markedoe.ondo.ui.screens.HomeActions
import com.markedoe.ondo.ui.screens.HomeUiState
import com.markedoe.ondo.ui.screens.VodActions
import com.markedoe.ondo.ui.screens.VodScreen
import com.markedoe.ondo.ui.screens.VodUiState
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.HEADER_DEBOUNCE_MS
import com.markedoe.ondo.ui.tv.HEADER_LOAD_DWELL_MS
import com.markedoe.ondo.ui.tv.LocalTitleHeaders
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TitleHeaders
import com.markedoe.ondo.ui.tv.TvHomeScreen
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
 * The header above a TV's posters: it shows the poster the remote rests on (its title, rating and synopsis), changes
 * once the remote rests on another one, not while it passes, and keeps its height when nothing is known of a title.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvFocusHeaderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun movie(id: String, name: String, plot: String? = null, rating: Double? = null, year: Int? = 2024) = ShowcaseItem.MovieItem(
        Movie(id = id, name = name, categoryId = "m-drama", streamUrl = "https://example.com/movie/$id.mp4", rating = rating, year = year, plot = plot, addedAt = plot?.let { 1_700_000_000L }),
    )

    private val lighthouse = movie("1", "The Quiet Lighthouse", plot = "A keeper counts the ships that never come.", rating = 7.0)
    private val orchard = movie("2", "Copper Orchard", plot = "Two sisters inherit a field of copper trees.", rating = 6.4)
    private val unknown = movie("3", "Untitled Reel", year = null)

    /** What the app's view model would say of them: TMDb's rating for the first, the provider's for the second. */
    private class FakeHeaders(private val known: Map<String, TitleHeader>) : TitleHeaders {
        val loaded = mutableListOf<String>()

        override fun header(item: ShowcaseItem): Flow<TitleHeader> = flowOf(known[item.key] ?: TitleHeader(item))

        override suspend fun load(item: ShowcaseItem) {
            loaded += item.key
        }
    }

    private val headers = FakeHeaders(
        mapOf(
            lighthouse.key to TitleHeader(lighthouse, genres = listOf("Drama", "Mystery"), tmdbRating = "7.3", plot = lighthouse.movie.plot),
            orchard.key to TitleHeader(orchard, genres = listOf("Drama"), providerRating = "6.4", plot = orchard.movie.plot),
        ),
    )

    private val drama = MediaCategory("m-drama", "Drama", ContentType.MOVIE)

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme { TelevisionFrame { CompositionLocalProvider(LocalTitleHeaders provides headers) { content() } } }
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

    private fun focus(item: ShowcaseItem) {
        compose.onNode(hasContentDescription(item.title) and hasClickAction()).requestFocus()
        compose.waitForIdle()
    }

    /** The texts of the nodes tagged [tag] in [within] (the header, Home's frame). */
    private fun texts(tag: String, within: String = "titleHeader"): List<String> =
        compose.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag(within)), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun posterBounds(item: ShowcaseItem): Rect = compose.onNode(hasContentDescription(item.title) and hasClickAction()).fetchSemanticsNode().boundsInRoot

    private fun moviesTab() = show {
        VodScreen(VodUiState(title = "Movies", categories = listOf(drama), rows = listOf(CategoryRow(drama, listOf(lighthouse, orchard, unknown)))), VodActions())
    }

    @Test
    fun theHeaderShowsTheFocusedTitleAndChangesWithTheFocus() {
        moviesTab()
        focus(lighthouse)
        advance(HEADER_DEBOUNCE_MS + 50)
        assertEquals(listOf("The Quiet Lighthouse"), texts("headerTitle"))
        assertEquals(listOf("7.3"), texts("headerRating"))
        assertTrue("TMDb's badge", compose.onAllNodesWithTag("headerTmdb", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertEquals(listOf("2024 · Drama, Mystery"), texts("headerFacts"))
        assertEquals(listOf("A keeper counts the ships that never come."), texts("headerPlot"))
        assertEquals("a date added", 1, texts("headerAdded").size)

        // Passing over a poster changes nothing; resting on it does.
        focus(orchard)
        advance(HEADER_DEBOUNCE_MS / 2)
        assertEquals(listOf("The Quiet Lighthouse"), texts("headerTitle"))
        advance(HEADER_DEBOUNCE_MS)
        assertEquals(listOf("Copper Orchard"), texts("headerTitle"))
        assertEquals(listOf("★ 6.4"), texts("headerRating"))
        assertTrue("no TMDb badge", compose.onAllNodesWithTag("headerTmdb", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertEquals(listOf("Two sisters inherit a field of copper trees."), texts("headerPlot"))

        // What is missing is asked for once the remote rests a moment longer, for that title only.
        assertTrue(headers.loaded.isEmpty() || headers.loaded.last() != orchard.key)
        advance(HEADER_LOAD_DWELL_MS + 50)
        assertEquals(orchard.key, headers.loaded.last())
    }

    @Test
    fun aTitleWithNothingKnownKeepsTheHeaderAndTheRowsInPlace() {
        moviesTab()
        focus(lighthouse)
        advance(HEADER_DEBOUNCE_MS + 50)
        val header = bounds("titleHeader")
        val plot = bounds("headerPlotRoom")
        assertTrue("the synopsis has its room", plot.width > 0f && plot.height > 0f)
        val poster = posterBounds(orchard)

        focus(unknown)
        advance(HEADER_DEBOUNCE_MS + 50)
        assertEquals(listOf("Untitled Reel"), texts("headerTitle"))
        assertTrue("no rating", texts("headerRating").isEmpty())
        assertTrue("no facts line text", texts("headerFacts").isEmpty())
        assertTrue("no date", texts("headerAdded").isEmpty())
        assertEquals("the header keeps its height", header.height, bounds("titleHeader").height, 0.5f)
        assertEquals("the synopsis keeps its room", plot.height, bounds("headerPlotRoom").height, 0.5f)
        assertEquals("the rows stay where they were", poster.top, posterBounds(orchard).top, 0.5f)
    }

    @Test
    fun homesFrameShowsTheFocusedPosterThenTheCarouselAgain() {
        val featured = HeroPage(orchard, meta = "Movie · 2024", inMyList = false, resumable = false, plot = orchard.movie.plot)
        val state = HomeUiState(playlistName = "Example", hero = listOf(featured), newMovies = listOf(lighthouse, unknown), hasVod = true)
        show { TvHomeScreen(state, HomeActions(), autoAdvance = false) }
        val full = bounds("heroFrame").height
        assertEquals(listOf("Copper Orchard"), texts("heroTitle", within = "heroFrame"))

        focus(lighthouse)
        advance(HEADER_DEBOUNCE_MS + 500)
        assertEquals(listOf("The Quiet Lighthouse"), texts("headerTitle", within = "heroFrame"))
        assertEquals(listOf("A keeper counts the ships that never come."), texts("headerPlot", within = "heroFrame"))
        assertTrue("the carousel's words give way", texts("heroTitle", within = "heroFrame").isEmpty())
        assertTrue("a smaller frame, for a whole row below", bounds("heroFrame").height < full)

        // Up to the buttons: the carousel is back, at its full height.
        compose.onNodeWithTag("heroPlay").requestFocus()
        compose.waitForIdle()
        advance(HEADER_DEBOUNCE_MS + 500)
        assertEquals(listOf("Copper Orchard"), texts("heroTitle", within = "heroFrame"))
        assertTrue(texts("headerTitle", within = "heroFrame").isEmpty())
        assertEquals(full, bounds("heroFrame").height, 0.5f)
    }

    /** The remote's Up from the first row of Home reaches the carousel's buttons, and the carousel is back at full height. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun upFromHomesRowsReachesTheCarouselButtons() {
        val featured = HeroPage(orchard, meta = "Movie · 2024", inMyList = false, resumable = false, plot = orchard.movie.plot)
        val state = HomeUiState(playlistName = "Example", hero = listOf(featured), newMovies = listOf(lighthouse, unknown), hasVod = true)
        show { TvHomeScreen(state, HomeActions(), autoAdvance = false) }
        val full = bounds("heroFrame").height

        focus(lighthouse)
        advance(HEADER_DEBOUNCE_MS + 500)
        assertTrue("a smaller frame while the rows have the focus", bounds("heroFrame").height < full)

        var tries = 0
        while (compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag("heroFrame"))).fetchSemanticsNodes().isEmpty()) {
            check(tries++ < 3) { "Up never reached the carousel's buttons" }
            compose.onAllNodes(isFocused()).onLast().performKeyInput { pressKey(Key.DirectionUp) }
            advance(HEADER_DEBOUNCE_MS + 500)
        }
        assertEquals(listOf("Copper Orchard"), texts("heroTitle", within = "heroFrame"))
        assertEquals(full, bounds("heroFrame").height, 0.5f)
    }
}
