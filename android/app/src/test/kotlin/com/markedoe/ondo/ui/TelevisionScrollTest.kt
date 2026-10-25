package com.markedoe.ondo.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.UncaughtErrors
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.FakeHttp
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.theme.OndoTheme
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Android TV: Back from a title's page lands where the remote was, far down a page as at its top: the same poster
 * has the focus, the page not scrolled back to its start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TelevisionScrollTest {
    @get:Rule val uncaught = UncaughtErrors()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val folder = TemporaryFolder()

    private var focusManager: FocusManager? = null

    /** Twenty categories of twelve movies each, after the fixture's: a page much taller than the screen. */
    private val categories = (1..20).map { MediaCategory("m-extra-$it", "Collection $it", ContentType.MOVIE) }
    private val movies = categories.flatMapIndexed { index, category ->
        (1..12).map { number ->
            Movie(
                id = "x$index-$number",
                name = "Film ${index + 1}-$number",
                categoryId = category.id,
                streamUrl = "http://example.com/movie/x$index-$number.mp4",
                year = 2020,
            )
        }
    }

    private fun start() {
        val store = AppStore(folder.root)
        store.savePlaylists(listOf(Fixtures.playlist))
        store.saveSettings(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID))
        val content = Fixtures.content.copy(
            movies = Fixtures.content.movies + movies,
            movieCategories = Fixtures.content.movieCategories + categories,
            fetchedAt = System.currentTimeMillis(),
        )
        store.saveContent(Fixtures.PLAYLIST_ID, content)
        val repository = OndoRepository(store, FakeHttp(emptyMap()), CoroutineScope(SupervisorJob() + Dispatchers.Main))
        val viewModel = OndoViewModel(repository, Labels(), minutes = flowOf(Fixtures.NOW))
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            focusManager = LocalFocusManager.current
            OndoTheme { CompositionLocalProvider(LocalCarouselAutoAdvance provides false) { OndoApp(viewModel) } }
        }
        compose.waitUntil(10_000) { exists("hero") }
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.mainClock.autoAdvance = false
        settle()
    }

    /** The main thread's clock and Compose's keep pace with real time, as on a device (pages load in the background). */
    private fun settle(ms: Long = 300) {
        val deadline = System.currentTimeMillis() + ms
        do {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            compose.mainClock.advanceTimeBy(20)
            compose.waitForIdle()
            uncaught.check()
        } while (System.currentTimeMillis() < deadline)
    }

    private fun waitFor(timeout: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out; the focus is on ${focused()}" }
            settle(60)
        }
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        val focusedNode = focusedNode()
        val direction = mapOf(Key.DirectionUp to FocusDirection.Up, Key.DirectionDown to FocusDirection.Down, Key.DirectionLeft to FocusDirection.Left, Key.DirectionRight to FocusDirection.Right)[key]
        if (focusedNode != null && direction != null && SemanticsProperties.EditableText in focusedNode.config) {
            compose.runOnIdle { focusManager?.moveFocus(direction) }
        } else {
            val target = if (focusedNode != null) compose.onAllNodes(isFocused()).onLast() else compose.onAllNodes(isRoot()).onLast()
            target.performKeyInput { pressKey(key) }
        }
        settle(120)
    }

    private fun pressUntil(key: Key, max: Int = 12, done: () -> Boolean) {
        repeat(max) {
            if (done()) return
            press(key)
        }
        check(done()) { "never reached with $key; the focus is on ${focused()}" }
    }

    private fun focusedNode(): SemanticsNode? = compose.onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull()

    private fun focusedTag(): String = focusedNode()?.config?.getOrNull(SemanticsProperties.TestTag).orEmpty()

    private fun focused(): String = focusedNode()?.let { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
            ?: node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
    } ?: "nothing"

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /** From the page to the drawer, onto the tab at [index] (Home is 0), opened; the focus enters the page. */
    private fun openTab(index: Int, page: String) {
        pressUntil(Key.DirectionLeft) { focusedTag().startsWith("tab") }
        press(Key.DirectionUp, times = 6)
        press(Key.DirectionDown, times = index + 1)
        press(Key.DirectionCenter)
        waitFor { exists(page) && !focusedTag().startsWith("tab") }
        settle(500)
    }

    /** OK on the focused poster, its page, Back: the focus is on that poster again. */
    private fun openAndComeBack(page: String) {
        val poster = focused()
        press(Key.DirectionCenter)
        waitFor { exists("detail") }
        settle(1_000)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor { exists(page) && !exists("detail") }
        settle(1_500)
        assertEquals("back on $page", poster, focused())
    }

    @Test
    fun backLandsOnThePosterItLeftFarDownTheMoviesTab() {
        start()
        openTab(1, "vod")
        press(Key.DirectionDown, times = 14)
        press(Key.DirectionRight, times = 2)
        assertNotEquals("Northern Lights", focused())
        openAndComeBack("vod")
    }

    @Test
    fun backLandsOnThePosterItLeftFarDownAllMovies() {
        start()
        openTab(1, "vod")
        // The category chips above the rows: All opens every movie in a grid.
        pressUntil(Key.DirectionUp) { focused() == "Drama" }
        pressUntil(Key.DirectionLeft) { focused() == "All" }
        press(Key.DirectionCenter)
        waitFor { exists("grid") }
        settle(1_000)
        pressUntil(Key.DirectionDown, max = 20) { focused().startsWith("Film") }
        press(Key.DirectionDown, times = 12)
        press(Key.DirectionRight)
        openAndComeBack("grid")
    }
}
