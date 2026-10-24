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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.FakeHttp
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.data.WatchEntry
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.theme.OndoTheme
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A "Continue watching" card taken away with a long press: OK held on a TV's remote, a finger held on a phone. */
@RunWith(RobolectricTestRunner::class)
class ContinueWatchingRemovalTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val folder = TemporaryFolder()

    private lateinit var repository: OndoRepository

    private val movie = Fixtures.glassCity
    private val entry = movieEntry(movie)

    private fun movieEntry(movie: Movie) = WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, movie.id, title = movie.name, lastWatchedAt = 1)

    private fun start(television: Boolean, entries: List<WatchEntry> = listOf(entry)) {
        val store = AppStore(folder.root)
        store.savePlaylists(listOf(Fixtures.playlist))
        store.saveSettings(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID))
        store.saveContent(Fixtures.PLAYLIST_ID, Fixtures.content.copy(fetchedAt = System.currentTimeMillis()))
        val library = entries.foldIndexed(LibraryState()) { index, library, entry -> library.recordStart(entry).updateProgress(entry.key, 1_800.0, 6_000.0, now = 2L + index) }
        store.saveLibrary(library)
        repository = OndoRepository(store, FakeHttp(emptyMap()), CoroutineScope(SupervisorJob() + Dispatchers.Main))
        val viewModel = OndoViewModel(repository, Labels(), minutes = flowOf(Fixtures.NOW))
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme { CompositionLocalProvider(LocalCarouselAutoAdvance provides false) { OndoApp(viewModel) } }
        }
        compose.waitUntil(10_000) { exists("home") }
        if (television) {
            compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
            compose.mainClock.autoAdvance = false
        }
        settle()
    }

    private fun settle(ms: Long = 300) {
        val deadline = System.currentTimeMillis() + ms
        do {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeBy(20)
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

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun focusedNode(): SemanticsNode? = compose.onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull()

    private fun focusedTag(): String = focusedNode()?.config?.getOrNull(SemanticsProperties.TestTag).orEmpty()

    private fun focused(): String = focusedNode()?.let { node ->
        (node.config.getOrNull(SemanticsProperties.TestTag) ?: "") + " " +
            (node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ").orEmpty())
    } ?: "(nothing)"

    private fun onTheCard(): Boolean =
        compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag("shelfResumeMovies"))).fetchSemanticsNodes().isNotEmpty()

    /** The focus is on a card of one of Home's rows (below the carousel). */
    private fun inARow(): Boolean {
        val shelf = SemanticsMatcher("in a row") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("shelf") == true }
        return compose.onAllNodes(isFocused() and hasAnyAncestor(shelf)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun focusedDescription(): String = focusedNode()?.config?.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()

    private fun press(key: Key) {
        compose.onAllNodes(isFocused()).onLast().performKeyInput { pressKey(key) }
        settle(150)
    }

    /**
     * OK held for a second, as a remote sends it: the key, then after a moment the same key repeated while it is held,
     * then up. Some cards see the repeat, others the time held; the question may open while OK is still down.
     */
    private fun holdOk() {
        val node = compose.onAllNodes(isFocused()).onLast()
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, repeat: Int) =
            node.performKeyPress(KeyEvent(AndroidKeyEvent(down, SystemClock.uptimeMillis(), action, AndroidKeyEvent.KEYCODE_DPAD_CENTER, repeat)))
        send(AndroidKeyEvent.ACTION_DOWN, 0)
        settle(500)
        for (repeat in 1..6) {
            // Once the question is open, the held key goes to it.
            if (exists("confirmDialog")) compose.onAllNodes(isFocused()).onLast().performKeyPress(KeyEvent(AndroidKeyEvent(down, SystemClock.uptimeMillis(), AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_CENTER, repeat)))
            else send(AndroidKeyEvent.ACTION_DOWN, repeat)
            settle(60)
        }
        if (exists("confirmDialog")) compose.onAllNodes(isFocused()).onLast().performKeyPress(KeyEvent(AndroidKeyEvent(down, SystemClock.uptimeMillis(), AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DPAD_CENTER, 0)))
        else send(AndroidKeyEvent.ACTION_UP, 0)
        settle(200)
    }

    private fun toTheCard() {
        waitFor { focusedNode() != null }
        repeat(6) { if (!onTheCard()) press(Key.DirectionDown) }
        assertTrue("the remote reached the card (${focused()})", onTheCard())
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun okHeldOnATvCardAsksThenTakesItAway() {
        start(television = true)
        toTheCard()
        holdOk()
        waitFor { exists("confirmDialog") }
        assertEquals("Cancel has the focus first", "confirmCancel", focusedTag())
        // Cancel: the card stays.
        press(Key.DirectionCenter)
        waitFor { !exists("confirmDialog") }
        assertNotNull(repository.state.value.library.progress(entry.key))
        assertTrue(exists("shelfResumeMovies"))

        waitFor { onTheCard() }
        holdOk()
        waitFor { exists("confirmDialog") }
        press(Key.DirectionRight)
        assertEquals("confirmOk", focusedTag())
        press(Key.DirectionCenter)
        waitFor { !exists("shelfResumeMovies") }
        assertNull(repository.state.value.library.progress(entry.key))
        settle(1_000)
        // The row went with its only card: the remote is on the row that took its place, not back on the carousel.
        assertTrue("the remote is on a row of Home (${focused()})", inARow())
        assertFalse(exists("confirmDialog"))
    }

    /** Of two cards, the one taken away hands the remote to the other, where the viewer was. */
    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun aTvCardTakenAwayHandsTheRemoteToTheNextOne() {
        val other = Fixtures.silentHarbor
        start(television = true, entries = listOf(entry, movieEntry(other)))
        toTheCard()
        val removed = focusedDescription()
        val kept = if (removed == movie.name) other.name else movie.name
        holdOk()
        waitFor { exists("confirmDialog") }
        press(Key.DirectionRight)
        assertEquals("confirmOk", focusedTag())
        press(Key.DirectionCenter)
        waitFor { compose.onAllNodes(hasContentDescription(removed) and hasAnyAncestor(hasTestTag("shelfResumeMovies"))).fetchSemanticsNodes().isEmpty() }
        settle(1_000)
        assertTrue("the remote is on the other card (${focused()})", onTheCard() && focusedDescription() == kept)
    }

    /** The Movies tab of a TV, where the row is called Continue watching. */
    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun okHeldOnTheMoviesTabTakesTheCardAway() {
        start(television = true)
        waitFor { focusedNode() != null }
        repeat(10) { if (!focusedTag().startsWith("tab")) press(Key.DirectionLeft) }
        repeat(8) { if (focusedTag() != "tabMovies") press(Key.DirectionDown) }
        assertEquals("tabMovies", focusedTag())
        press(Key.DirectionCenter)
        val card = hasText(movie.name) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)
        waitFor { compose.onAllNodes(card and isFocused()).fetchSemanticsNodes().isNotEmpty() || run { press(Key.DirectionRight); false } }
        holdOk()
        waitFor { exists("confirmDialog") }
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        waitFor { repository.state.value.library.progress(entry.key) == null }
        waitFor { compose.onAllNodes(card).fetchSemanticsNodes().isEmpty() }
        settle(1_000)
        // The first row of the page takes the remote, not the drawer.
        assertTrue("the remote is on the Movies page (${focused()})", compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag("vod"))).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun aFingerHeldOnAPhoneCardOffersToTakeItAway() {
        start(television = false)
        val card = hasText(movie.name) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)
        compose.onNodeWithTag("home").performScrollToNode(card)
        compose.onNode(card).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("removeFromHistory").performClick()
        compose.waitUntil(5_000) { repository.state.value.library.progress(entry.key) == null }
        compose.waitUntil(5_000) { compose.onAllNodes(card).fetchSemanticsNodes().isEmpty() }
    }
}
