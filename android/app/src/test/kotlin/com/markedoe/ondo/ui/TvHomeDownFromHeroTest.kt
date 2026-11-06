package com.markedoe.ondo.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.library.RowOrder
import com.markedoe.ondo.core.library.Selections
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.ui.components.HeroPage
import com.markedoe.ondo.ui.model.ChannelRow
import com.markedoe.ondo.ui.model.ResumeItem
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.HomeActions
import com.markedoe.ondo.ui.screens.HomeUiState
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.FocusEntry
import com.markedoe.ondo.ui.tv.LocalFocusMemory
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TvHomeScreen
import com.markedoe.ondo.ui.tv.TvNavigationFrame
import com.markedoe.ondo.ui.tv.rememberFocusMemory
import androidx.compose.runtime.CompositionLocalProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home on a TV: Down from the featured titles' buttons lands in the first row with the row's name whole on screen,
 * whatever kind of row comes first (every row Home can show is listed here: a new kind must be added), at every frame
 * while the frame above shrinks, and when the row came after Home was first drawn (a selection's titles worked out
 * later). Then Down and Up along the rows and Down again from the buttons keep it so.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvHomeDownFromHeroTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun movie(id: String, name: String) = ShowcaseItem.MovieItem(
        Movie(id = id, name = name, categoryId = "m-drama", streamUrl = "https://example.com/movie/$id.mp4", year = 2024, addedAt = 1_700_000_000L),
    )

    private fun series(id: String, name: String) = ShowcaseItem.SeriesItem(Series(id = id, name = name, categoryId = "s-drama", year = 2024))

    private val movies = (1..8).map { movie("$it", "Example Film $it") }
    private val shows = (1..8).map { series("s$it", "Example Series $it") }
    private val resumeMovies = movies.take(4).map { ResumeItem("resume:${it.key}", it.title, subtitle = "2024", imageUrl = null, progress = 0.4f, minutesLeft = 7, hasPage = true, showcase = it) }
    private val resumeSeries = shows.take(4).map { ResumeItem("resume:${it.key}", it.title, subtitle = "S1 E2", imageUrl = null, progress = 0.4f, minutesLeft = 7, isSeries = true, hasPage = true, showcase = it) }
    private val channels = (1..6).map {
        ChannelRow(Channel(id = "c$it", name = "Example Channel $it", categoryId = "news", streamUrl = "https://example.com/live/c$it.m3u8"), nowTitle = "Morning News", nowProgress = 0.3f, nextTitle = null)
    }
    private val selection = SelectionRow("pick", "Marc preselection", listOf(Selections.decade(2020)), emptyMap(), movies.drop(2) + shows.take(3))
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
    private fun state(first: String, withSelection: Boolean = true) = HomeUiState(
        playlistName = "Example Playlist",
        hero = hero,
        continueWatching = resumeMovies + resumeSeries,
        myList = movies.reversed(),
        newMovies = movies,
        newSeries = shows,
        trending = movies.drop(1),
        hasVod = true,
        order = listOf(first) + kinds.keys.filter { it != first },
        selections = if (withSelection) mapOf(selectionKey to selection) else emptyMap(),
        channels = channels,
    )

    private var homeState by mutableStateOf<HomeUiState?>(null)
    private var round by mutableIntStateOf(0)
    private var started = false

    /** Home anew (its focus memory and its list too) with [initial], the focus entering it as on a device. */
    private fun show(initial: HomeUiState) {
        homeState = initial
        if (started) {
            round++
            advance(1_000)
            return
        }
        started = true
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme {
                TelevisionFrame {
                    TvNavigationFrame(TopLevel.HOME, showBar = true, onSelect = {}, onSearch = {}, onSettings = {}) {
                        key(round) {
                            // As the app's pages are: a focus memory, and the focus entering the page.
                            CompositionLocalProvider(LocalFocusMemory provides rememberFocusMemory()) {
                                FocusEntry { homeState?.let { TvHomeScreen(it, HomeActions(), autoAdvance = false) } }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.mainClock.autoAdvance = false
        advance(1_000)
    }

    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun bounds(tag: String): DpRect = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun focusedIn(tag: String) = compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag(tag))).fetchSemanticsNodes().isNotEmpty()

    private fun press(key: Key) {
        compose.onAllNodes(isFocused()).onLast().performKeyInput { pressKey(key) }
    }

    private fun onPlay() {
        compose.onNodeWithTag("heroPlay").requestFocus()
        advance(1_000)
        assertTrue("Play has the focus", compose.onNodeWithTag("heroPlay").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true)
    }

    /** The row [shelf]'s name and cards below the frame, never under it, and whole above the screen's bottom once still. */
    private fun assertRowWhole(shelf: String, step: String, settled: Boolean) {
        val frame = bounds("hero")
        val row = bounds(shelf)
        assertTrue("$step: $shelf starts at ${row.top}, under the frame ending at ${frame.bottom}", row.top >= frame.bottom - 0.5.dp)
        if (settled) {
            val card = compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag(shelf))).onLast().getUnclippedBoundsInRoot()
            assertTrue("$step: the focused card ends at ${card.bottom}, below the screen", card.bottom <= 540.dp + 0.5.dp)
        }
    }

    /** Down from Play: the focus in [shelf], its name never cut at any frame while the frame shrinks, nor after. */
    private fun assertDownLandsWhole(shelf: String, step: String) {
        press(Key.DirectionDown)
        repeat(40) { frame ->
            advance(16)
            assertRowWhole(shelf, "$step, frame $frame", settled = false)
        }
        advance(1_000)
        assertTrue("$step: the focus in $shelf", focusedIn(shelf))
        assertRowWhole(shelf, step, settled = true)
    }

    @Test
    fun everyKindOfRowIsListed() {
        assertEquals(RowOrder.homeBuiltIns(hasVod = true, collections = false).toSet() + selectionKey, kinds.keys)
        assertEquals(kinds.keys.toList(), state(RowOrder.HOME_CONTINUE_MOVIES).shownRows.sortedBy { kinds.keys.indexOf(it) })
    }

    @Test
    fun downFromTheFeaturedTitlesShowsTheFirstRowsNameWholeForEveryKindOfRow() {
        for ((key, shelf) in kinds) {
            show(state(key))
            onPlay()
            assertDownLandsWhole(shelf, key)
        }
    }

    @Test
    fun aSelectionRowThatComesLateShowsItsNameWholeToo() {
        val shelf = kinds.getValue(selectionKey)
        // Home drawn first without the selection's row: its titles are worked out a moment later.
        show(state(selectionKey, withSelection = false))
        onPlay()
        homeState = state(selectionKey)
        advance(1_000)
        assertTrue("the selection's row has come", compose.onAllNodesWithTag(shelf).fetchSemanticsNodes().isNotEmpty())
        assertDownLandsWhole(shelf, "the late row")
    }

    @Test
    fun downAgainFromTheFeaturedTitlesAfterGoingDownTheRowsShowsTheRowWhole() {
        val first = kinds.getValue(RowOrder.HOME_NEW_MOVIES)
        show(state(RowOrder.HOME_NEW_MOVIES))
        onPlay()
        assertDownLandsWhole(first, "Down once")
        // Two rows down, then back up to the buttons.
        repeat(2) {
            press(Key.DirectionDown)
            advance(1_000)
        }
        repeat(6) {
            if (!focusedIn("hero")) {
                press(Key.DirectionUp)
                advance(1_000)
            }
        }
        assertTrue("back on the featured titles' buttons", focusedIn("hero"))
        press(Key.DirectionDown)
        advance(1_500)
        val shelf = kinds.values.first(::focusedIn)
        assertRowWhole(shelf, "Down again", settled = true)
    }
}
