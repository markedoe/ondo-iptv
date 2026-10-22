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
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.core.model.EpgProgram
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.FakeHttp
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.screens.GuideActions
import com.markedoe.ondo.ui.screens.GuideRow
import com.markedoe.ondo.ui.screens.GuideScreen
import com.markedoe.ondo.ui.screens.GuideUiState
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.FocusMemory
import com.markedoe.ondo.ui.tv.LocalFocusMemory
import com.markedoe.ondo.ui.tv.TelevisionFrame
import java.time.Duration
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
import org.robolectric.Shadows.shadowOf
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

    /** What TalkBack would read for the focused item: its description, else its texts, else its test tag. */
    private fun focused(): String {
        val node = compose.onNode(isFocused()).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
            ?: node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
    }

    /** The focused item's test tag. */
    private fun focusedTag(): String =
        compose.onNode(isFocused()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.TestTag).orEmpty()

    /** From the page, to the rail, onto the tab at [index] (Home is 0), and opens it. */
    private fun openTab(index: Int) {
        repeat(6) { if (!focusedTag().startsWith("tab")) press(Key.DirectionLeft) }
        press(Key.DirectionUp, times = 5)
        press(Key.DirectionDown, times = index)
        press(Key.DirectionCenter)
        // The page's content (computed in the background), then the focus entering it: the test's clocks (the main
        // thread's and Compose's) keep pace with real time, as on a device.
        val deadline = System.currentTimeMillis() + 5_000
        do {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            compose.mainClock.advanceTimeBy(20)
            compose.waitForIdle()
        } while (focusedTag().startsWith("tab") && System.currentTimeMillis() < deadline)
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
    fun everyTabOpensWithTheFocusInIt() {
        start()
        for ((index, page) in listOf(1 to "vod", 2 to "vod", 3 to "live", 4 to "myList")) {
            openTab(index)
            compose.onNodeWithTag(page).assertExists()
            assertTrue("tab $index: the focus is in the page, not on ${focused()}", !focusedTag().startsWith("tab"))
        }
    }

    @Test
    fun onLiveTvHoldingOkKeepsTheChannelInMyList() {
        start()
        openTab(3)
        assertTrue("the first channel has the focus: ${focused()}", focused().contains("Ondo News"))
        assertTrue("no favourite yet", compose.onAllNodesWithContentDescription("In My List").fetchSemanticsNodes().isEmpty())
        compose.onRoot().performKeyInput { keyDown(Key.DirectionCenter) }
        // The long press waits on the main thread's clock.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().performKeyInput { keyUp(Key.DirectionCenter) }
        // The Live TV page is computed again in the background.
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("In My List").fetchSemanticsNodes().isNotEmpty() }
        // Held, not pressed: the channel did not start.
        compose.onNodeWithTag("live").assertExists()
    }

    @Test
    fun theGuideStartsOnWhatIsOnNow() {
        val now = Fixtures.NOW
        val channel = Fixtures.channels.first()
        val airing = EpgProgram("c1.test", now - 600_000, now + 1_200_000, "Morning News")
        val later = EpgProgram("c1.test", now + 1_200_000, now + 3_000_000, "Weather")
        val window = UiMappers.guideWindow(now)
        val state = GuideUiState(rows = listOf(GuideRow(channel, listOf(airing, later))), total = 1, start = window.first, end = window.last, now = now, hasGuide = true)
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme {
                CompositionLocalProvider(LocalFocusMemory provides FocusMemory()) { TelevisionFrame { GuideScreen(state, GuideActions()) } }
            }
        }
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.waitForIdle()
        assertTrue("the programme on air has the focus: ${focused()}", focused().contains("Morning News"))
        press(Key.DirectionRight)
        assertTrue("then the next one: ${focused()}", focused().contains("Weather"))
    }

    @Test
    fun leftFromPlayOnTheFirstPageReachesTheTabs() {
        start()
        press(Key.DirectionLeft)
        assertTrue("the focus is on a tab: ${focused()}", focusedTag().startsWith("tab"))
    }
}
