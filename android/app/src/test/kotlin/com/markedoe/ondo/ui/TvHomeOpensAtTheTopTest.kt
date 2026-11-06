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
import androidx.compose.ui.test.hasAnyAncestor
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
import com.markedoe.ondo.ui.tv.FocusEntry
import com.markedoe.ondo.ui.tv.LocalFocusMemory
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TvHomeScreen
import com.markedoe.ondo.ui.tv.TvNavigationFrame
import com.markedoe.ondo.ui.tv.rememberFocusMemory
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

    private fun show(initial: HomeUiState) {
        homeState = initial
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme {
                TelevisionFrame {
                    TvNavigationFrame(TopLevel.HOME, showBar = true, onSelect = {}, onSearch = {}, onSettings = {}) {
                        CompositionLocalProvider(LocalFocusMemory provides rememberFocusMemory()) {
                            FocusEntry { TvHomeScreen(homeState, HomeActions(), autoAdvance = false) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.mainClock.autoAdvance = false
        advance(1_500)
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
    private fun assertOpensAtTheTop(rows: HomeUiState, firstShelf: String) {
        show(rows)
        assertTrue("meanwhile, the focus on the first row ($firstShelf)", focusedIn(firstShelf))
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

    @Test
    fun newMoviesFirstThenTheFeaturedTitles() = assertOpensAtTheTop(
        HomeUiState(playlistName = "Example Playlist", newMovies = movies, newSeries = shows, hasVod = true),
        "shelfNewMovies",
    )
}
