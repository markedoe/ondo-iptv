package com.markedoe.ondo.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
import com.markedoe.ondo.ui.screens.HelpScreen
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

    private fun waitForTag(tag: String) {
        compose.waitUntil(5_000) { compose.onAllNodes(isFocused()).fetchSemanticsNodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == tag } }
    }

    /** Whether the top bar is there for TalkBack too. */
    private fun barShown(): Boolean =
        compose.onAllNodesWithTag("topBar").fetchSemanticsNodes().any { SemanticsProperties.HideFromAccessibility !in it.config }

    /** From the page, up to the top bar, onto the tab at [index] (Home is 0), and opens it. */
    private fun openTab(index: Int) {
        repeat(40) { if (!focusedTag().startsWith("tab")) press(Key.DirectionUp) }
        press(Key.DirectionLeft, times = 7)
        press(Key.DirectionRight, times = index)
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

    /** Above the featured titles: which playlist Home shows, and when it was last updated from the provider. */
    @Test
    fun homeShowsThePlaylistAndItsLastUpdateAboveTheCarousel() {
        start()
        compose.onNodeWithTag("lastUpdate", useUnmergedTree = true).assertIsDisplayed().assert(hasText("Updated", substring = true))
        compose.onNodeWithTag("lastUpdate", useUnmergedTree = true).assert(hasText(Fixtures.playlist.name, substring = true))
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
    fun rightPastTheLastButtonTurnsTheCarouselPage() {
        start()
        assertTrue(heroShows("Northern Lights"))
        // Play → My List → More Info → the next title, the focus staying on More Info.
        press(Key.DirectionRight, times = 2)
        compose.onNodeWithTag("heroInfo").assertIsFocused()
        press(Key.DirectionRight)
        compose.onNodeWithTag("heroInfo").assertIsFocused()
        assertTrue(heroShows("Harbor Lights"))
        assertTrue(!heroShows("Northern Lights"))
    }

    private fun heroTitle(): String =
        compose.onNodeWithTag("heroTitle", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString(" ").orEmpty()

    /** Left before Play goes back a title, Right past More Info forward: both wrap around, the focus never leaves. */
    @Test
    fun theRemoteTurnsTheCarouselBackAndForth() {
        start()
        val first = heroTitle()
        // Before the first title: the last one, the focus staying on Play.
        press(Key.DirectionLeft)
        compose.onNodeWithTag("heroPlay").assertIsFocused()
        val last = heroTitle()
        assertTrue("the carousel turned back from $first", last != first)
        // Play → My List → More Info → past the last title: the first one again.
        press(Key.DirectionRight, times = 3)
        compose.onNodeWithTag("heroInfo").assertIsFocused()
        assertEquals(first, heroTitle())
        press(Key.DirectionRight)
        val second = heroTitle()
        assertTrue("the carousel turned on from $first", second != first)
        // Back along the buttons, still on the second title, then back to the first.
        press(Key.DirectionLeft, times = 2)
        compose.onNodeWithTag("heroPlay").assertIsFocused()
        assertEquals(second, heroTitle())
        press(Key.DirectionLeft)
        compose.onNodeWithTag("heroPlay").assertIsFocused()
        assertEquals(first, heroTitle())
    }

    /** In Arabic, Right before Play (the buttons run from the right) goes back a title. */
    @Test
    @Config(qualifiers = "ar-rSA-w960dp-h540dp-land-television-xhdpi")
    fun inArabicRightBeforeTheFirstButtonTurnsTheCarouselBack() {
        start()
        val first = heroTitle()
        press(Key.DirectionRight)
        compose.onNodeWithTag("heroPlay").assertIsFocused()
        assertTrue("the carousel turned back from $first", heroTitle() != first)
        press(Key.DirectionLeft, times = 3)
        compose.onNodeWithTag("heroInfo").assertIsFocused()
        assertEquals(first, heroTitle())
    }

    /** In Arabic the buttons run from the right: Left past the last one turns the page, Right goes back along them. */
    @Test
    @Config(qualifiers = "ar-rSA-w960dp-h540dp-land-television-xhdpi")
    fun inArabicLeftPastTheLastButtonTurnsTheCarouselPage() {
        start()
        val first = heroTitle()
        // Play → My List → More Info, leftwards.
        press(Key.DirectionLeft, times = 2)
        compose.onNodeWithTag("heroInfo").assertIsFocused()
        press(Key.DirectionRight)
        compose.onNodeWithTag("heroMyList").assertIsFocused()
        assertEquals(first, heroTitle())
        press(Key.DirectionLeft)
        compose.onNodeWithTag("heroInfo").assertIsFocused()
        // Left past the last button: the next title, the focus staying on More Info.
        press(Key.DirectionLeft)
        compose.onNodeWithTag("heroInfo").assertIsFocused()
        assertTrue("the carousel turned from $first", heroTitle() != first)
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

    /**
     * The guide's window moves on every half hour (it starts two hours back, from the half hour): a viewer browsing
     * later programmes stays where they are, on the same programme.
     */
    @Test
    fun theGuideKeepsItsPlaceWhenItsWindowMovesOn() {
        val halfHour = 1_800_000L
        val start = Fixtures.NOW / halfHour * halfHour - 4 * halfHour
        val now = start + 4 * halfHour + 600_000
        val (channel, other) = Fixtures.channels
        val programmes = (0 until 36).map { EpgProgram("c1.test", start + it * halfHour, start + (it + 1) * halfHour, "Show $it") }
        // A guide may give two programmes at the same time.
        val overlapping = (0 until 36).flatMap { listOf("News $it", "Sport $it").map { title -> EpgProgram("c2.test", start + it * halfHour, start + (it + 1) * halfHour, title) } }
        // As the app makes it: the programmes of the window only.
        fun guideAt(windowStart: Long, time: Long): GuideUiState {
            val end = windowStart + 28 * halfHour
            fun inWindow(list: List<EpgProgram>) = list.filter { it.end > windowStart && it.start < end }
            val rows = listOf(GuideRow(channel, inWindow(programmes)), GuideRow(other, inWindow(overlapping)))
            return GuideUiState(rows = rows, total = 2, start = windowStart, end = end, now = time, hasGuide = true)
        }
        var state by mutableStateOf(guideAt(start, now))
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme {
                CompositionLocalProvider(LocalFocusMemory provides FocusMemory()) { TelevisionFrame { GuideScreen(state, GuideActions()) } }
            }
        }
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.waitForIdle()
        assertTrue("the programme on air has the focus: ${focused()}", focused().contains("Show 4"))
        // Six hours later along the row.
        press(Key.DirectionRight, times = 12)
        assertTrue(focused(), focused().contains("Show 16"))
        val before = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot
        // Half an hour on: the window starts half an hour later, without its first programme.
        state = guideAt(start + halfHour, now + halfHour)
        compose.waitForIdle()
        val guide = compose.onNodeWithTag("guide").fetchSemanticsNode().boundsInRoot
        val after = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot
        // A programme scrolled out of the guide has empty bounds.
        assertTrue("the focused programme is still on screen: $after in $guide", !after.isEmpty && after.left >= guide.left && after.right <= guide.right)
        assertTrue("the same programme has the focus: ${focused()}", focused().contains("Show 16"))
        assertEquals("the programme stays where it was", before.left, after.left, 2f)
    }

    @Test
    fun theHelpStartsWithTheRemote() {
        compose.setContent { OndoTheme { HelpScreen(onBack = {}, onSupport = {}) } }
        compose.onNodeWithTag("help-remote").performClick()
        compose.onNodeWithText("hold OK on a channel", substring = true).assertExists()
        compose.onNodeWithText("Restart plays the programme from its start", substring = true).assertExists()
        // No touch screen on a TV.
        compose.onNodeWithTag("help-player").assertDoesNotExist()
    }

    @Test
    fun upFromPlayReachesTheTopBarOnHome() {
        start()
        press(Key.DirectionUp)
        assertEquals("tabHome", focusedTag())
    }

    @Test
    fun upFromATabsPageLandsOnThatTab() {
        start()
        openTab(1)
        press(Key.DirectionDown)
        repeat(40) { if (!focusedTag().startsWith("tab")) press(Key.DirectionUp) }
        assertEquals("tabMovies", focusedTag())
    }

    @Test
    fun backGoesToTheTopBarThenHomeThenOut() {
        start()
        openTab(2)
        // From the page: the top bar, on the tab shown.
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitForTag("tabSeries")
        // From the bar: Home.
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("home").fetchSemanticsNodes().isNotEmpty() }
        // From Home's bar, Back is the system's (out of the app).
        repeat(40) { if (!focusedTag().startsWith("tab")) press(Key.DirectionUp) }
        compose.runOnIdle { assertTrue(!compose.activity.onBackPressedDispatcher.hasEnabledCallbacks()) }
    }

    /** Down from the bar, the remote is back on what it left, not on whatever lies below the tab. */
    @Test
    fun downFromTheTopBarGoesBackWhereTheRemoteWas() {
        start()
        press(Key.DirectionRight)
        val left = focusedTag()
        assertTrue("on a button of the featured title: $left", left.startsWith("hero") && left != "heroPlay")
        press(Key.DirectionUp)
        assertEquals("tabHome", focusedTag())
        press(Key.DirectionDown)
        assertEquals(left, focusedTag())
    }

    /**
     * Far down a tab's page the bar stays on screen; Up goes back up the rows (not into the bar above them), Back brings
     * the page's top back with the focus on the bar.
     */
    @Test
    fun farDownAPageTheBarStaysAndBackBringsTheTopBack() {
        start()
        press(Key.DirectionDown, times = 4)
        val deep = focusedTag()
        assertTrue("the bar stays on screen", barShown())
        compose.onNodeWithTag("tabHome").assertIsDisplayed()
        press(Key.DirectionUp)
        assertFalse("Up went into the bar from far down the page", focusedTag().startsWith("tab"))
        press(Key.DirectionDown)
        assertEquals(deep, focusedTag())
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitForTag("tabHome")
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals("the bar keeps the focus", "tabHome", focusedTag())
        compose.onNodeWithTag("hero").assertIsDisplayed()
        // The page is at its top: Down starts from its first item.
        press(Key.DirectionDown)
        assertEquals("heroPlay", focusedTag())
    }

    @Test
    fun aMoviePageHasNoBackButtonAndOpensOnPlay() {
        start()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("detail").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("detailPlay").assertIsFocused()
        assertTrue(compose.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isEmpty())
        // No top bar over a movie: Up from Play stays on the page.
        press(Key.DirectionUp)
        compose.onNodeWithTag("detailPlay").assertIsFocused()
    }
}
