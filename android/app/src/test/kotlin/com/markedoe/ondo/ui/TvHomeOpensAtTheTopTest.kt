package com.markedoe.ondo.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.snapshots.Snapshot
import org.robolectric.Shadows.shadowOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.ui.components.HeroPage
import com.markedoe.ondo.ui.model.ResumeItem
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.HomeActions
import com.markedoe.ondo.ui.screens.HomeUiState
import com.markedoe.ondo.ui.theme.OndoTheme
import androidx.lifecycle.Lifecycle
import com.markedoe.ondo.ui.tv.FocusEntry
import com.markedoe.ondo.ui.tv.FocusMemory
import com.markedoe.ondo.ui.tv.LocalFocusMemory
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TvHomeScreen
import com.markedoe.ondo.ui.tv.TvNavigationFrame
import com.markedoe.ondo.ui.tv.rememberFocusMemory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home on a TV opens at its top, Play focused, though its featured titles come after its rows (the catalogue or what
 * the device kept of TMDb still being read): the focus waits on the first card meanwhile, then goes to Play, the frame
 * full size above the rows, the rows from their first. Whatever row came first (continue watching, new titles).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvHomeOpensAtTheTopTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun movie(id: String, name: String) = ShowcaseItem.MovieItem(
        Movie(id = id, name = name, categoryId = "m-drama", streamUrl = "https://example.com/movie/$id.mp4", year = 2024, addedAt = 1_700_000_000L),
    )

    private val movies = (1..8).map { movie("$it", "Example Film $it") }
    private val shows = (1..4).map { ShowcaseItem.SeriesItem(Series(id = "s$it", name = "Example Series $it", categoryId = "s-drama", year = 2024)) }
    private val resume = movies.take(3).map { ResumeItem("resume:${it.key}", it.title, subtitle = "2024", imageUrl = null, progress = 0.4f, minutesLeft = 7, hasPage = true, showcase = it) } +
        shows.take(2).map { ResumeItem("resume:${it.key}", it.title, subtitle = "S1 E2", imageUrl = null, progress = 0.4f, minutesLeft = 7, isSeries = true, hasPage = true, showcase = it) }
    private val hero = listOf(HeroPage(movies[5], meta = "Movie · 2024", inMyList = false, resumable = false, plot = "A keeper counts the ships that never come."))

    private var homeState by mutableStateOf(HomeUiState(playlistName = "Example Playlist"))
    private var memory: FocusMemory? = null

    private fun show(initial: HomeUiState, settleMs: Long = 1_500) {
        homeState = initial
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme {
                TelevisionFrame {
                    TvNavigationFrame(TopLevel.HOME, showBar = true, onSelect = {}, onSearch = {}, onSettings = {}) {
                        CompositionLocalProvider(LocalFocusMemory provides rememberFocusMemory().also { memory = it }) {
                            FocusEntry { TvHomeScreen(homeState, HomeActions(), autoAdvance = false) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.mainClock.autoAdvance = false
        advance(settleMs)
    }

    private fun advance(ms: Long) {
        // What the test changed reaches the screen (the main looper idle, the snapshot's changes sent).
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun focusedTag(): String =
        compose.onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull()?.config?.getOrNull(SemanticsProperties.TestTag).orEmpty()

    private fun focusedIn(tag: String) = compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag(tag))).fetchSemanticsNodes().isNotEmpty()

    /** The featured titles come [after] the rows: Home is then at its top, Play focused, the first row whole below. */
    private fun assertOpensAtTheTop(rows: HomeUiState, firstShelf: String, paused: Boolean = false) {
        show(rows)
        assertTrue("meanwhile, the focus on the first row ($firstShelf)", focusedIn(firstShelf))
        if (paused) {
            // Something over Ondo a moment (a TV's overlay, its standby), then Ondo again.
            compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            advance(500)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            advance(500)
        }
        homeState = rows.copy(hero = hero)
        advance(1_500)
        fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
        assertTrue("Play there: home ${exists("home")}, hero ${exists("hero")}, heroFrame ${exists("heroFrame")}, heroPlay ${exists("heroPlay")}, focus on ${focusedTag()}\n${compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 8)}", exists("heroPlay") == 1)
        assertTrue("Play has the focus, not ${focusedTag()}", compose.onNodeWithTag("heroPlay").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true)
        val frame = compose.onNodeWithTag("heroFrame", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("the frame full size: ${frame.height}", frame.height >= 260.dp)
        val shelf = compose.onNodeWithTag(firstShelf, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val heroBottom = compose.onNodeWithTag("hero", useUnmergedTree = true).getUnclippedBoundsInRoot().bottom
        assertTrue("the rows from their first: $firstShelf at ${shelf.top}, the frame ending at $heroBottom", shelf.top >= heroBottom && shelf.top <= heroBottom + 24.dp)
    }

    @Test
    fun continueWatchingFirstThenTheFeaturedTitles() = assertOpensAtTheTop(
        HomeUiState(playlistName = "Example Playlist", continueWatching = resume, newMovies = movies, newSeries = shows, hasVod = true),
        "shelfResumeMovies",
    )

    /**
     * The featured titles there from the start, but the focus put on the first card before Play could take it (Play not
     * focusable yet as the app starts, the page's entry giving the focus to what it finds), then on Play: the rows
     * stay at their top, never left scrolled with their first row's name under the frame.
     */
    @Test
    fun aCardFocusedBeforePlayLeavesTheRowsAtTheirTop() {
        for ((rows, shelf) in listOf(
            HomeUiState(playlistName = "Example Playlist", hero = hero, continueWatching = resume, newMovies = movies, hasVod = true) to "shelfResumeMovies",
            HomeUiState(playlistName = "Example Playlist", hero = hero, newMovies = movies, newSeries = shows, hasVod = true) to "shelfNewMovies",
        )) {
            if (homeState.hero.isEmpty()) show(rows) else {
                homeState = rows
                advance(1_000)
            }
            compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(shelf))).onFirst().requestFocus()
            advance(1_000)
            compose.onNodeWithTag("heroPlay").requestFocus()
            advance(1_500)
            assertTrue("$shelf: Play has the focus", compose.onNodeWithTag("heroPlay").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true)
            val heroBottom = compose.onNodeWithTag("hero", useUnmergedTree = true).getUnclippedBoundsInRoot().bottom
            val top = compose.onNodeWithTag(shelf, useUnmergedTree = true).getUnclippedBoundsInRoot().top
            assertTrue("the rows at their top: $shelf at $top, the frame ending at $heroBottom", top >= heroBottom + 17.dp && top <= heroBottom + 19.dp)
        }
    }

    private fun firstCardFocused(shelf: String): Boolean = compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(shelf))).onFirst()
        .fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true

    /** [shelf], its name too, wholly below the top bar (84 dp), the list at its top: the playlist's name above it. */
    private fun assertWholeBelowTheBarAtTheTop(shelf: String, step: String) {
        val top = compose.onNodeWithTag(shelf, useUnmergedTree = true).getUnclippedBoundsInRoot().top
        assertTrue("$step: $shelf starts at $top, under the top bar", top >= 84.dp)
        val name = compose.onNode(hasText("Example Playlist") and hasAnyAncestor(hasTestTag("home")), useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("$step: the list at its top, the playlist's name at ${name.top}", name.top >= 0.dp && name.bottom <= top)
    }

    /**
     * No featured titles (not read yet at a cold start, or none at all), two rows to continue watching: from the first
     * frames, the first card of the FIRST row has the focus, the list at its top. Then the movies' row comes above the
     * series' one, which had the focus (the photo of a TV: Series in progress first on screen, the row above cut under
     * the bar): the list stays at its top, the focus on the new first row.
     */
    @Test
    fun withoutFeaturedTitlesTheFirstRowsFirstCardAndTheListAtItsTop() {
        val both = HomeUiState(playlistName = "Example Playlist", continueWatching = resume, newMovies = movies, hasVod = true)
        show(both, settleMs = 300)
        assertTrue("the first row's first card, from the first frames: ${focusedTag()}", firstCardFocused("shelfResumeMovies"))
        assertWholeBelowTheBarAtTheTop("shelfResumeMovies", "both rows")
        advance(3_000)
        assertTrue("still there, no featured titles", firstCardFocused("shelfResumeMovies"))
        assertWholeBelowTheBarAtTheTop("shelfResumeMovies", "no featured titles")
    }

    @Test
    fun aRowComingAboveTheFocusedOneLeavesTheListAtItsTop() {
        val seriesOnly = HomeUiState(playlistName = "Example Playlist", continueWatching = resume.filter { it.isSeries }, newMovies = movies, hasVod = true)
        show(seriesOnly, settleMs = 300)
        assertTrue("the series' row first, its first card focused", firstCardFocused("shelfResumeSeries"))
        homeState = seriesOnly.copy(continueWatching = resume)
        advance(300)
        assertTrue("the movies' row, now first, has the focus: ${focusedTag()}", firstCardFocused("shelfResumeMovies"))
        assertWholeBelowTheBarAtTheTop("shelfResumeMovies", "the movies' row came above")
        // Then the featured titles: Play, the frame full size.
        homeState = homeState.copy(hero = hero)
        advance(1_500)
        assertTrue("Play has the focus", compose.onNodeWithTag("heroPlay").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true)
    }

    @Test
    fun continueWatchingFirstThenAPauseThenTheFeaturedTitles() = assertOpensAtTheTop(
        HomeUiState(playlistName = "Example Playlist", continueWatching = resume, newMovies = movies, newSeries = shows, hasVod = true),
        "shelfResumeMovies",
        paused = true,
    )

    @Test
    fun newMoviesFirstThenTheFeaturedTitles() = assertOpensAtTheTop(
        HomeUiState(playlistName = "Example Playlist", newMovies = movies, newSeries = shows, hasVod = true),
        "shelfNewMovies",
    )
}
