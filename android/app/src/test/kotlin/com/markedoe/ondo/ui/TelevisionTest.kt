package com.markedoe.ondo.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.FakeHttp
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.theme.OndoTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Android TV / Google TV: the whole app with the remote's five-way pad only (Google's TV app quality, TV-DP). The
 * focus starts on Home's Play, moves through the shelves, opens a page and comes back to the card it left.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TelevisionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val folder = TemporaryFolder()

    private fun start() {
        val store = AppStore(folder.root)
        store.savePlaylists(listOf(Fixtures.playlist))
        store.saveSettings(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID))
        store.saveContent(Fixtures.PLAYLIST_ID, Fixtures.content.copy(fetchedAt = System.currentTimeMillis()))
        val repository = OndoRepository(store, FakeHttp(emptyMap()), CoroutineScope(SupervisorJob() + Dispatchers.Main))
        val viewModel = OndoViewModel(repository, Labels(), minutes = flowOf(Fixtures.NOW))
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme { CompositionLocalProvider(LocalCarouselAutoAdvance provides false) { OndoApp(viewModel) } }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("hero").fetchSemanticsNodes().isNotEmpty() }
        // A TV has no touch screen: the remote's keys put it in keyboard mode, where items take the focus.
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.waitForIdle()
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** What TalkBack would read for the focused item: its description, else its test tag. */
    private fun focused(): String {
        val node = compose.onNode(isFocused()).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
    }

    @Test
    fun homeOpensWithPlayFocused() {
        start()
        compose.onNodeWithTag("heroPlay").assertIsFocused()
    }

    @Test
    fun theRemoteMovesThroughTheShelvesAndComesBackToTheCardItLeft() {
        start()
        press(Key.DirectionDown)
        assertEquals("Northern Lights", focused())
        press(Key.DirectionRight)
        assertEquals("The Last Signal", focused())
        press(Key.DirectionCenter)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("detail").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        // The detail page opens on Play.
        compose.onNodeWithTag("detailPlay").assertIsFocused()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("home").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertEquals("The Last Signal", focused())
    }

    /** The title the carousel shows (the shelves name their posters in descriptions, not texts). */
    private fun heroShows(title: String) = compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun rightPastTheLastItemTurnsTheCarouselPage() {
        start()
        assertTrue(heroShows("Northern Lights"))
        // Play → My List → the poster beside them → the next page, on its Play.
        press(Key.DirectionRight, times = 3)
        compose.onNodeWithTag("heroPlay").assertIsFocused()
        assertTrue(heroShows("Harbor Lights"))
        assertTrue(!heroShows("Northern Lights"))
        // Left past Play goes back a page.
        press(Key.DirectionLeft)
        compose.onNodeWithTag("heroPlay").assertIsFocused()
        assertTrue(heroShows("Northern Lights"))
    }

    @Test
    fun leftFromPlayOnTheFirstPageReachesTheTabs() {
        start()
        press(Key.DirectionLeft)
        assertTrue("the focus is on a tab: ${focused()}", focused().startsWith("tab"))
    }
}
