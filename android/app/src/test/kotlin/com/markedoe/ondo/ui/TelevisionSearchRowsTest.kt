package com.markedoe.ondo.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.UncaughtErrors
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.FakeHttp
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.SearchUiState
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.FocusMemory
import com.markedoe.ondo.ui.tv.LocalFocusMemory
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TvSearchScreen
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Search on a TV, its rows as the words change: each row starts again from its first title whenever its titles change
 * (it never stays scrolled to where an earlier title now is), Down from the field lands on a row's first card, and
 * Back from a title still lands on the card it was opened from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TelevisionSearchRowsTest {
    @get:Rule val uncaught = UncaughtErrors()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val folder = TemporaryFolder()

    private var focusManager: FocusManager? = null
    private var inputModes: InputModeManager? = null

    private val films = (1..30).map { Movie("f$it", "Film $it", categoryId = Fixtures.drama.id, streamUrl = "http://example.com/movie/f$it.mp4", year = 2020) }

    private fun results(query: String, movies: List<Movie>) = SearchUiState(query = query, movies = movies.map { ShowcaseItem.MovieItem(it) })

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
            check(System.currentTimeMillis() < deadline) { "timed out; the focus is on ${focused()}; on screen: ${films.map { it.name }.filter(::onScreen)}" }
            settle(60)
        }
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun focusedNode(): SemanticsNode? = compose.onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull()

    private fun focusedTag(): String = focusedNode()?.config?.getOrNull(SemanticsProperties.TestTag).orEmpty()

    private fun focused(): String = focusedNode()?.let { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
    } ?: "nothing"

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        val node = focusedNode()
        val direction = mapOf(Key.DirectionUp to FocusDirection.Up, Key.DirectionDown to FocusDirection.Down, Key.DirectionLeft to FocusDirection.Left, Key.DirectionRight to FocusDirection.Right)[key]
        if (node != null && direction != null && SemanticsProperties.EditableText in node.config) {
            compose.runOnIdle { focusManager?.moveFocus(direction) }
        } else {
            val target = if (node != null) compose.onAllNodes(isFocused()).onLast() else compose.onAllNodes(isRoot()).onLast()
            target.performKeyInput { pressKey(key) }
        }
        settle(150)
    }

    /** Whether the poster of [title] is on screen (laid out within the window), not only composed. */
    private fun onScreen(title: String): Boolean {
        val width = compose.onAllNodes(isRoot()).fetchSemanticsNodes().first().size.width
        return compose.onAllNodes(hasContentDescription(title)).fetchSemanticsNodes().any { it.boundsInRoot.left >= 0f && it.boundsInRoot.left < width }
    }

    private fun startScreen(initial: SearchUiState): (SearchUiState) -> Unit {
        var state by mutableStateOf(initial)
        compose.setContent {
            inputModes = LocalInputModeManager.current
            focusManager = LocalFocusManager.current
            OndoTheme {
                TelevisionFrame {
                    CompositionLocalProvider(LocalFocusMemory provides FocusMemory()) {
                        TvSearchScreen(state, onQuery = {}, onOpen = {}, onPlayChannel = {}, onFilters = {}, onPerson = {})
                    }
                }
            }
        }
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.mainClock.autoAdvance = false
        settle()
        return { state = it }
    }

    /** "film", then "film 30", then back to "film": the row shows Film 1 again, not the end where Film 30 now is. */
    @Test
    fun aRowStartsFromItsFirstTitleWhateverWasTypedBefore() {
        val show = startScreen(results("film", films))
        assertTrue(onScreen("Film 1"))
        show(results("film 30", films.takeLast(1)))
        settle()
        show(results("film", films))
        settle()
        assertTrue("the row starts from Film 1", onScreen("Film 1"))
        assertFalse("not scrolled to the end, where Film 30 is now", onScreen("Film 30"))
        // Words that keep some of the titles, in another order: the row starts from its first one again.
        show(results("film 2", listOf(films[1]) + films.subList(19, 29)))
        settle()
        show(results("fil", films.reversed()))
        settle()
        assertTrue("the row starts from Film 30, now first", onScreen("Film 30"))
        assertFalse(onScreen("Film 2"))
    }

    /** Down from the field lands on the first card of the first row, not on the card below the middle of the field. */
    @Test
    fun downFromTheFieldLandsOnTheFirstCard() {
        val show = startScreen(results("film", films))
        waitFor { focusedTag() == "searchField" }
        press(Key.DirectionDown)
        assertEquals("Film 1", focused())
        // Along the row, then back up to the field and down again, with other words: the first card again.
        press(Key.DirectionRight, times = 6)
        assertEquals("Film 7", focused())
        // Up from a card on the right reaches Filters, above it; Left, the field.
        press(Key.DirectionUp)
        if (focusedTag() == "searchFilters") press(Key.DirectionLeft)
        assertEquals("searchField", focusedTag())
        show(results("film 1", listOf(films[0]) + films.subList(9, 19)))
        settle()
        press(Key.DirectionDown)
        assertEquals("Film 1", focused())
        assertTrue(onScreen("Film 1"))
    }

    /** Back from a title opened far along a row: the remote is on its card again, the row where it was. */
    @Test
    fun backFromATitleLandsOnItsCard() {
        val store = AppStore(folder.root)
        store.savePlaylists(listOf(Fixtures.playlist))
        store.saveSettings(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID))
        store.saveContent(Fixtures.PLAYLIST_ID, Fixtures.content.copy(movies = films, fetchedAt = System.currentTimeMillis()))
        val repository = OndoRepository(store, FakeHttp(emptyMap()), CoroutineScope(SupervisorJob() + Dispatchers.Main))
        val viewModel = OndoViewModel(repository, Labels(), minutes = flowOf(Fixtures.NOW))
        compose.setContent {
            inputModes = LocalInputModeManager.current
            focusManager = LocalFocusManager.current
            OndoTheme { CompositionLocalProvider(LocalCarouselAutoAdvance provides false) { OndoApp(viewModel) } }
        }
        compose.waitUntil(10_000) { exists("home") }
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.mainClock.autoAdvance = false
        settle()
        waitFor { focusedNode() != null }
        repeat(40) { if (!focusedTag().startsWith("tab")) press(Key.DirectionUp) }
        repeat(8) { if (focusedTag() != "tabSearch") press(Key.DirectionRight) }
        press(Key.DirectionCenter)
        waitFor { exists("searchField") && focusedTag() == "searchField" }
        press(Key.DirectionCenter)
        compose.onNodeWithTag("searchField").performTextInput("film")
        waitFor { onScreen("Film 1") }
        press(Key.DirectionDown)
        assertEquals("Film 1", focused())
        press(Key.DirectionRight, times = 12)
        assertEquals("Film 13", focused())
        press(Key.DirectionCenter)
        waitFor { exists("detail") }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor { exists("searchField") && focused() == "Film 13" }
        assertTrue(onScreen("Film 13"))
        assertFalse("the row where it was", onScreen("Film 1"))
    }
}
