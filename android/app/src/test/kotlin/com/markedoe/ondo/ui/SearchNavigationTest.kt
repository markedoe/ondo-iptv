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
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.FakeHttp
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.screens.SearchFilters
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

/**
 * Search is a place of its own, not a page of the tab it was opened from: a tab opens on its own page after a search
 * (not on Search again), Search opens empty, and only coming back from a result keeps the words. On a TV it is a
 * whole page without the drawer, which Back closes.
 */
@RunWith(RobolectricTestRunner::class)
class SearchNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val folder = TemporaryFolder()

    private lateinit var viewModel: OndoViewModel
    private var focusManager: FocusManager? = null
    private var inputModes: InputModeManager? = null

    private fun start() {
        val store = AppStore(folder.root)
        store.savePlaylists(listOf(Fixtures.playlist))
        store.saveSettings(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID))
        store.saveContent(Fixtures.PLAYLIST_ID, Fixtures.content.copy(fetchedAt = System.currentTimeMillis()))
        val repository = OndoRepository(store, FakeHttp(emptyMap()), CoroutineScope(SupervisorJob() + Dispatchers.Main))
        viewModel = OndoViewModel(repository, Labels(), minutes = flowOf(Fixtures.NOW))
        compose.setContent {
            inputModes = LocalInputModeManager.current
            focusManager = LocalFocusManager.current
            OndoTheme { CompositionLocalProvider(LocalCarouselAutoAdvance provides false) { OndoApp(viewModel) } }
        }
        compose.waitUntil(10_000) { exists("hero") }
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
            check(System.currentTimeMillis() < deadline) { "timed out; the focus is on ${focused()} (${focusedTag()})" }
            settle(60)
        }
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun back() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
    }

    private fun typed(): String =
        compose.onNodeWithTag("searchField").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    // Phone

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        settle()
    }

    /** The magnifying glass of the page shown. */
    private fun openSearchHere() {
        compose.onAllNodesWithContentDescription("Search").onFirst().performClick()
        waitFor { exists("searchField") }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun onAPhoneATabOpensOnItsOwnPageAfterASearch() {
        start()
        tap("tabMovies")
        waitFor { exists("vod") }
        openSearchHere()
        compose.onNodeWithTag("searchField").performTextInput("Glass")
        settle()
        // Straight to the tab it was opened from, and through another tab.
        tap("tabMovies")
        waitFor { exists("vod") }
        assertFalse("Movies, not Search again", exists("searchField"))
        openSearchHere()
        tap("tabHome")
        waitFor { exists("home") }
        tap("tabMovies")
        waitFor { exists("vod") }
        assertFalse("Movies, not Search again", exists("searchField"))
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun onAPhoneSearchOpensEmptyAndKeepsItsWordsOnlyBackFromAResult() {
        start()
        openSearchHere()
        compose.onNodeWithTag("searchField").performTextInput("Glass")
        compose.runOnIdle { viewModel.setSearchFilters(SearchFilters(fromYear = 2020)) }
        waitFor { compose.onAllNodesWithContentDescription(Fixtures.glassCity.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithContentDescription(Fixtures.glassCity.name).onFirst().performClick()
        waitFor { exists("detail") }
        back()
        waitFor { exists("searchField") }
        assertEquals("back from a result, the words are kept", "Glass", typed())
        assertEquals(2020, viewModel.searchFilters.value.fromYear)
        back()
        waitFor { exists("home") }
        openSearchHere()
        assertEquals("opened again, Search is empty", "", typed())
        assertEquals(SearchFilters(), viewModel.searchFilters.value)
    }

    // TV

    private fun startTv() {
        start()
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.mainClock.autoAdvance = false
        settle()
    }

    private fun focusedNode(): SemanticsNode? = compose.onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull()

    private fun focusedTag(): String = focusedNode()?.config?.getOrNull(SemanticsProperties.TestTag).orEmpty()

    private fun focused(): String = focusedNode()?.let { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
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

    private fun pressUntil(key: Key, max: Int = 12, done: () -> Boolean) {
        repeat(max) {
            if (done()) return
            press(key)
        }
        check(done()) { "never reached with $key; the focus is on ${focused()} (${focusedTag()})" }
    }

    /** From the page to the drawer, onto [tag] (a tab, or the magnifying glass), OK. */
    private fun fromDrawer(tag: String) {
        pressUntil(Key.DirectionLeft) { focusedTag().startsWith("tab") }
        pressUntil(Key.DirectionUp, max = 8) { focusedTag() == tag || focusedTag() == "tabSearch" }
        pressUntil(Key.DirectionDown, max = 8) { focusedTag() == tag }
        press(Key.DirectionCenter)
        settle(500)
    }

    /** OK on the TV's search field opens its keyboard; then the words. */
    private fun typeOnTv(text: String) {
        pressUntil(Key.DirectionUp, max = 4) { focusedTag() == "searchField" }
        press(Key.DirectionCenter)
        compose.onNodeWithTag("searchField").performTextInput(text)
        settle()
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun onATvSearchIsAPageOfItsOwnThatBackCloses() {
        startTv()
        fromDrawer("tabMovies")
        waitFor { exists("vod") }
        fromDrawer("tabSearch")
        waitFor { exists("searchField") }
        assertFalse("no drawer beside Search", exists("drawer"))
        typeOnTv("Glass")
        // The keyboard closes first, then Search.
        back()
        if (exists("searchField")) back()
        waitFor { exists("vod") && !exists("searchField") }
        assertTrue("back on Movies, with its drawer", exists("drawer"))
        // Movies again from the drawer: Movies, not Search.
        fromDrawer("tabHome")
        waitFor { exists("home") }
        fromDrawer("tabMovies")
        waitFor { exists("vod") }
        assertFalse("Movies, not Search again", exists("searchField"))
        // And Search opens empty.
        fromDrawer("tabSearch")
        waitFor { exists("searchField") }
        compose.onNodeWithTag("searchField").assertTextContains("", substring = true)
        assertEquals("", typed())
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun onATvTheResultsNameTheirTitles() {
        startTv()
        fromDrawer("tabSearch")
        waitFor { exists("searchField") }
        typeOnTv("Lights")
        waitFor { compose.onAllNodes(hasContentDescription(Fixtures.northernLights.name)).fetchSemanticsNodes().isNotEmpty() }
        // The title is written under the poster, not only read out.
        waitFor { compose.onAllNodesWithTag("resultTitle", useUnmergedTree = true).fetchSemanticsNodes().any { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString() == Fixtures.northernLights.name } }
    }
}
