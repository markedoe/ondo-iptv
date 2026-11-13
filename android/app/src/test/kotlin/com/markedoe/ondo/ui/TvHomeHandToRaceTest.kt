package com.markedoe.ondo.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.markedoe.ondo.FocusTrace
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.ui.components.HeroPage
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.HomeActions
import com.markedoe.ondo.ui.screens.HomeUiState
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.FocusEntry
import com.markedoe.ondo.ui.tv.FocusMemory
import com.markedoe.ondo.ui.tv.LocalFocusMemory
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TvHomeScreen
import com.markedoe.ondo.ui.tv.TvNavigationFrame
import com.markedoe.ondo.ui.tv.rememberFocusMemory
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * TEMPORARY (branch claude/tvfocus-debug only): Down pressed 0..4 frames after Home's first item changes, Play focused
 * and no key pressed yet on Home. Home's move to its top (toTop) checks for a key after one frame, then hands the focus
 * to Play through FocusMemory.handTo, which only takes effect at the next recomposition (Play's focusMemory effect):
 * a press in that frame is expected to be undone. Two triggers: a row coming above the first one, and the carousel's
 * placeholder replaced by the carousel and its rows (what the welcome screen's Save goes through).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvHomeHandToRaceTest(private val trigger: String, private val frames: Int) {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}, Down after {1} frames")
        fun cases(): List<Array<Any>> = listOf("rowAbove", "heroArrives").flatMap { trigger -> (0..4).map { arrayOf<Any>(trigger, it) } }
    }

    private fun movie(id: String, name: String) = ShowcaseItem.MovieItem(
        Movie(id = id, name = name, categoryId = "m-drama", streamUrl = "https://example.com/movie/$id.mp4", year = 2024, addedAt = 1_700_000_000L),
    )

    private val movies = (1..8).map { movie("$it", "Example Film $it") }
    private val shows = (1..4).map { ShowcaseItem.SeriesItem(Series(id = "s$it", name = "Example Series $it", categoryId = "s-drama", year = 2024)) }
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
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun focusedTag(): String =
        compose.onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull()?.config?.getOrNull(SemanticsProperties.TestTag).orEmpty()

    private fun focusedIn(tag: String) = compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag(tag))).fetchSemanticsNodes().isNotEmpty()

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun downPressedAsHomeMovesToItsTopStaysInTheRows() {
        val whole = HomeUiState(playlistName = "Example Playlist", hero = hero, newMovies = movies, newSeries = shows, hasVod = true, updatedAt = 1_700_000_000_000L)
        val before = when (trigger) {
            // Play focused, the series' row first; then the movies' row comes above it.
            "rowAbove" -> HomeUiState(playlistName = "Example Playlist", hero = hero, newSeries = shows, hasVod = true, updatedAt = 1_700_000_000_000L)
            // The first download: the carousel's place kept (a wheel), nothing else; then the carousel and its rows.
            else -> HomeUiState(playlistName = "Example Playlist", isLoading = true)
        }
        show(before)
        FocusTrace.log("hand-to race $trigger/$frames: shown, focus on ${focusedTag()}, restore=${memory?.restore}")
        homeState = whole
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
        repeat(frames) { frame ->
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idle()
            FocusTrace.log("hand-to race $trigger/$frames: after frame ${frame + 1}, focus on ${focusedTag()}, restore=${memory?.restore}, movies' row there: ${exists("shelfNewMovies")}")
        }
        val target = compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty()
        val onPlay = focusedTag() == "heroPlay"
        FocusTrace.log("hand-to race $trigger/$frames: Down, focus on ${focusedTag()}, restore=${memory?.restore}")
        (if (target) compose.onAllNodes(isFocused()).onLast() else compose.onAllNodesWithTag("home").onLast()).performKeyInput { pressKey(Key.DirectionDown) }
        FocusTrace.log("hand-to race $trigger/$frames: just after Down, focus on ${focusedTag()}, in the rows: ${focusedIn("homeRows")}, restore=${memory?.restore}")
        advance(1_500)
        FocusTrace.log("hand-to race $trigger/$frames: 1.5 s later, focus on ${focusedTag()}, in the rows: ${focusedIn("homeRows")}, restore=${memory?.restore}")
        // Only a press made on Play is the failing test's case (Play focused, Down): the others are logged, then skipped.
        assumeTrue("Down was pressed on ${focusedTag()}, not Play", onPlay)
        assertTrue("$trigger, Down after $frames frames: the remote stays in the rows, not on ${focusedTag()}", focusedIn("homeRows"))
    }
}
