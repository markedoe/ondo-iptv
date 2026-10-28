package com.markedoe.ondo.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.UncaughtErrors
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.PersonScreen
import com.markedoe.ondo.ui.screens.PersonUiState
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.FocusMemory
import com.markedoe.ondo.ui.tv.LocalFocusMemory
import com.markedoe.ondo.ui.tv.TelevisionFrame
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A person's page on a TV: the remote goes down the movies, then on to the series below the line, card by card. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TelevisionPersonTest {
    @get:Rule val uncaught = UncaughtErrors()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun settle(ms: Long = 200) {
        val deadline = System.currentTimeMillis() + ms
        do {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            compose.mainClock.advanceTimeBy(20)
            compose.waitForIdle()
            uncaught.check()
        } while (System.currentTimeMillis() < deadline)
    }

    private fun focused(): String = compose.onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull()?.let { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
    } ?: "nothing"

    private fun press(key: Key) {
        val target = if (focused() != "nothing") compose.onAllNodes(isFocused()).onLast() else compose.onAllNodes(isRoot()).onLast()
        target.performKeyInput { pressKey(key) }
        settle(150)
    }

    @Test
    fun theRemoteGoesFromTheMoviesDownToTheSeries() {
        val movies = (1..8).map { Movie("m$it", "Film $it", categoryId = Fixtures.drama.id, streamUrl = "http://example.com/movie/m$it.mp4", year = 2020 - it) }
        val state = PersonUiState(
            "Ann Example",
            movies = movies.map { ShowcaseItem.MovieItem(it) },
            series = listOf(Fixtures.harborLights, Fixtures.theArchive).map { ShowcaseItem.SeriesItem(it) },
            searchedOnline = true,
        )
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme { TelevisionFrame { CompositionLocalProvider(LocalFocusMemory provides FocusMemory()) { PersonScreen(state, onOpen = {}, onBack = {}) } } }
        }
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.mainClock.autoAdvance = false
        settle()
        repeat(4) { if (focused() != "Film 1") press(Key.DirectionDown) }
        assertEquals("the first movie, below the name", "Film 1", focused())
        // The cards on the first movie's line (by their middle: the focused card grows around it).
        fun middle(name: String) = compose.onAllNodes(hasContentDescription(name)).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.center?.y
        val columns = movies.count { middle(it.name)?.let { y -> kotlin.math.abs(y - middle("Film 1")!!) < 1f } == true }
        val visited = mutableListOf(focused())
        repeat(4) {
            press(Key.DirectionDown)
            visited += focused()
        }
        val expected = listOf("Film 1") + (1 + columns..movies.size step columns).map { "Film $it" } + Fixtures.harborLights.name
        assertEquals("down the first column of movies, then the first series ($columns columns)", expected, visited.take(expected.size))
    }
}
