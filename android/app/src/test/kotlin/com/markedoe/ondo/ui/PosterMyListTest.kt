package com.markedoe.ondo.ui

import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.pressKey
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.FakeHttp
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.theme.OndoTheme
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
import org.robolectric.shadows.ShadowToast

/** OK held on a TV's poster adds the title to My List, or takes it away, without opening its page. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class PosterMyListTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val folder = TemporaryFolder()

    private lateinit var repository: OndoRepository

    private val movieTitles = Fixtures.content.movies.map { it.name }.toSet()

    private fun start() {
        val store = AppStore(folder.root)
        store.savePlaylists(listOf(Fixtures.playlist))
        store.saveSettings(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID))
        store.saveContent(Fixtures.PLAYLIST_ID, Fixtures.content.copy(fetchedAt = System.currentTimeMillis()))
        repository = OndoRepository(store, FakeHttp(emptyMap()), CoroutineScope(SupervisorJob() + Dispatchers.Main))
        val viewModel = OndoViewModel(repository, Labels(), minutes = flowOf(Fixtures.NOW))
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme { CompositionLocalProvider(LocalCarouselAutoAdvance provides false) { OndoApp(viewModel) } }
        }
        compose.waitUntil(10_000) { exists("home") }
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.mainClock.autoAdvance = false
        settle()
    }

    private fun settle(ms: Long = 300) {
        val deadline = System.currentTimeMillis() + ms
        do {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            compose.mainClock.advanceTimeBy(20)
            compose.waitForIdle()
        } while (System.currentTimeMillis() < deadline)
    }

    private fun waitFor(timeout: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out; the focus is on ${focused()}" }
            settle(60)
        }
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun focusedNode(): SemanticsNode? = compose.onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull()

    private fun focusedTag(): String = focusedNode()?.config?.getOrNull(SemanticsProperties.TestTag).orEmpty()

    private fun focused(): String = focusedNode()?.let { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
            ?: node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
    } ?: "(nothing)"

    private fun focusedIn(tag: String) = compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag(tag))).fetchSemanticsNodes().isNotEmpty()

    private fun press(key: Key) {
        compose.onAllNodes(isFocused()).onLast().performKeyInput { pressKey(key) }
        settle(150)
    }

    private fun pressUntil(key: Key, max: Int = 14, done: () -> Boolean) {
        repeat(max) {
            if (done()) return
            press(key)
        }
        check(done()) { "never reached with $key; the focus is on ${focused()}" }
    }

    /** OK held for a second, as a remote sends it: the key, then the same key repeated while it is held, then up. */
    private fun holdOk() {
        val node = compose.onAllNodes(isFocused()).onLast()
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, repeat: Int) =
            node.performKeyPress(KeyEvent(AndroidKeyEvent(down, SystemClock.uptimeMillis(), action, AndroidKeyEvent.KEYCODE_DPAD_CENTER, repeat)))
        send(AndroidKeyEvent.ACTION_DOWN, 0)
        settle(500)
        for (repeat in 1..6) {
            send(AndroidKeyEvent.ACTION_DOWN, repeat)
            settle(60)
        }
        send(AndroidKeyEvent.ACTION_UP, 0)
        settle(300)
    }

    private fun inMyList(id: String, kind: ItemKind = ItemKind.MOVIE) =
        repository.state.value.library.isFavorite(Fixtures.PLAYLIST_ID, kind, id)

    /** The focused poster shows the check mark of My List. */
    private fun focusedShowsTheMark() =
        compose.onAllNodes(hasTestTag("inMyList") and hasAnyAncestor(isFocused()), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun movie(title: String) = Fixtures.content.movies.first { it.name == title }

    @Test
    fun okHeldOnACategorysPosterAddsItToMyListThenTakesItAway() {
        start()
        // Movies, then a category's posters from its chip.
        pressUntil(Key.DirectionUp, max = 20) { focusedTag().startsWith("tab") }
        pressUntil(Key.DirectionRight) { focusedTag() == "tabMovies" }
        press(Key.DirectionCenter)
        waitFor { exists("vod") && !focusedTag().startsWith("tab") }
        pressUntil(Key.DirectionDown) { focused() in movieTitles }
        pressUntil(Key.DirectionUp) { focused() == "All" || focused() == "Drama" }
        pressUntil(Key.DirectionRight) { focused() == "Drama" }
        press(Key.DirectionCenter)
        waitFor { exists("grid") && focusedIn("grid") }
        pressUntil(Key.DirectionDown) { focused() == "Northern Lights" }
        val id = movie("Northern Lights").id
        assertFalse(inMyList(id))
        assertFalse(focusedShowsTheMark())

        holdOk()
        assertTrue("OK held added it to My List", inMyList(id))
        assertEquals("Added to My List", ShadowToast.getTextOfLatestToast())
        assertFalse("the title's page did not open", exists("detail"))
        assertTrue("still on the posters", exists("grid"))
        assertEquals("Northern Lights", focused())
        assertTrue("the poster shows it is in My List", focusedShowsTheMark())

        holdOk()
        assertFalse("OK held again took it away", inMyList(id))
        assertEquals("Removed from My List", ShadowToast.getTextOfLatestToast())
        assertFalse(exists("detail"))
        assertFalse(focusedShowsTheMark())

        // A short OK still opens the page.
        press(Key.DirectionCenter)
        waitFor { exists("detail") }
    }

    @Test
    fun okHeldOnAHomePosterAddsItToMyList() {
        start()
        waitFor { focusedTag() == "heroPlay" }
        pressUntil(Key.DirectionDown) { focusedIn("shelfNewMovies") }
        val title = focused()
        val id = movie(title).id
        holdOk()
        assertTrue("$title added to My List", inMyList(id))
        assertEquals("Added to My List", ShadowToast.getTextOfLatestToast())
        assertFalse("the title's page did not open", exists("detail"))
        assertTrue(compose.onAllNodes(hasTestTag("inMyList") and hasAnyAncestor(hasContentDescription(title)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }
}
