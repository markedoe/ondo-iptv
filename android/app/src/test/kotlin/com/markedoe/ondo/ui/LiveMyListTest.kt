package com.markedoe.ondo.ui

import android.content.res.Configuration
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.FakeHttp
import com.markedoe.ondo.data.Favorite
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.screens.LiveActions
import com.markedoe.ondo.ui.screens.LiveScreen
import com.markedoe.ondo.ui.screens.LiveUiState
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
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Live TV opens on My List, the profile's favourite channels, while it has some that show; otherwise on all channels,
 * with a hint on how to add one. The choice made stays from tab to tab; a new profile, a new playlist or a new launch
 * opens on My List again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class LiveMyListTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val folder = TemporaryFolder()

    private lateinit var repository: OndoRepository
    private lateinit var viewModel: OndoViewModel

    private val worldReport = Fixtures.channels[1]
    private val stadiumOne = Fixtures.channels[2]

    private fun favourites(vararg channels: Channel) =
        LibraryState(favorites = channels.map { Favorite(Fixtures.PLAYLIST_ID, ItemKind.CHANNEL, it.id, it.name, addedAt = Fixtures.NOW) })

    /** The surfaces Live TV is drawn on. A new one (a tablet layout, a car…) is added here. */
    private enum class Surface(val uiMode: Int) {
        PHONE(Configuration.UI_MODE_TYPE_NORMAL),
        TELEVISION(Configuration.UI_MODE_TYPE_TELEVISION),
    }

    @Test
    fun liveTvOpensOnMyListOnEverySurfaceAndOnAllChannelsWithoutFavourites() {
        val cases = listOf(
            // What the profile has, its library, whether Live TV opens on My List.
            Triple("no favourite", LibraryState(), false),
            Triple("favourites", favourites(stadiumOne, worldReport), true),
            // The only favourite is in a hidden category: hidden stays hidden, and the page is not empty.
            Triple(
                "favourites only in a hidden category",
                favourites(stadiumOne).settingCategoryHidden(Fixtures.PLAYLIST_ID, Fixtures.sports.id, hidden = true),
                false,
            ),
        )
        var surface by mutableStateOf(Surface.PHONE)
        var ui by mutableStateOf(LiveUiState())
        compose.setContent {
            val base = LocalConfiguration.current
            val configuration = Configuration(base).apply { uiMode = (base.uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or surface.uiMode }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                OndoTheme { LiveScreen(ui, LiveActions()) }
            }
        }
        for (shown in Surface.entries) {
            for ((name, library, onMyList) in cases) {
                val case = "$shown, $name"
                compose.runOnIdle {
                    surface = shown
                    // As the view model gives it on opening: nothing chosen yet.
                    ui = UiMappers.live(Fixtures.state(library = library), categoryId = null, favoritesOnly = null, now = Fixtures.NOW)
                }
                compose.waitForIdle()
                assertEquals(case, onMyList, ui.showingFavorites)
                if (onMyList) {
                    compose.onNodeWithTag("liveMyList").assertIsSelected()
                    compose.onNodeWithTag("liveAll").assertIsNotSelected()
                    assertEquals(case, listOf(stadiumOne.id, worldReport.id).sorted(), ui.channels.map { it.channel.id }.sorted())
                    assertFalse("$case: no hint once My List has channels", exists("liveMyListHint"))
                } else {
                    compose.onNodeWithTag("liveAll").assertIsSelected()
                    compose.onNodeWithTag("liveMyList").assertIsNotSelected()
                    assertTrue("$case: all channels, never an empty page", ui.channels.isNotEmpty())
                    assertTrue("$case: how to add a channel to My List", exists("liveMyListHint"))
                }
                // Hidden stays hidden, on every choice.
                assertFalse(case, ui.channels.any { it.channel.categoryId in UiMappers.hiddenCategoryIds(Fixtures.state(library = library)) })
                // My List comes first among the choices.
                val choices = compose.onAllNodesWithTag("liveMyList").fetchSemanticsNodes().single().boundsInRoot
                val all = compose.onAllNodesWithTag("liveAll").fetchSemanticsNodes().single().boundsInRoot
                assertTrue("$case: My List before All", choices.left < all.left)
            }
        }
    }

    // The app, from the view model to the screen.

    private fun start(library: LibraryState, television: Boolean = false) {
        val store = AppStore(folder.root)
        store.savePlaylists(listOf(Fixtures.playlist))
        store.saveSettings(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID))
        store.saveLibrary(library)
        store.saveContent(Fixtures.PLAYLIST_ID, Fixtures.content.copy(fetchedAt = System.currentTimeMillis()))
        repository = OndoRepository(store, FakeHttp(emptyMap()), CoroutineScope(SupervisorJob() + Dispatchers.Main))
        viewModel = OndoViewModel(repository, Labels(), minutes = flowOf(Fixtures.NOW))
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme { CompositionLocalProvider(LocalCarouselAutoAdvance provides false) { OndoApp(viewModel) } }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("hero").fetchSemanticsNodes().isNotEmpty() }
        if (television) compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
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

    private fun waitFor(timeout: Long = 8_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out; the focus is on ${focused()} (${focusedTag()})" }
            settle(60)
        }
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun focusedNode(): SemanticsNode? = compose.onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull()

    private fun focusedInteraction(): SemanticsNodeInteraction = compose.onAllNodes(isFocused()).onLast()

    private fun focusedTag(): String = focusedNode()?.config?.getOrNull(SemanticsProperties.TestTag).orEmpty()

    private fun focused(): String = focusedNode()?.let { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
            ?: node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
    } ?: "(nothing)"

    private fun focusedText(): String = focusedNode()?.config?.getOrNull(SemanticsProperties.Text)?.joinToString(" ").orEmpty()

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        val target = if (focusedNode() != null) focusedInteraction() else compose.onAllNodes(isRoot()).onLast()
        target.performKeyInput { pressKey(key) }
        settle(120)
    }

    private fun pressUntil(key: Key, max: Int = 12, done: () -> Boolean) {
        repeat(max) {
            if (done()) return
            press(key)
        }
        check(done()) { "never reached with $key; the focus is on ${focused()}" }
    }

    private fun back() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle(500)
    }

    /** From anywhere on a tab page: up to the top bar, along to [tab], OK. */
    private fun openTabWithRemote(tab: String) {
        pressUntil(Key.DirectionUp, max = 40) { focusedTag().startsWith("tab") }
        pressUntil(Key.DirectionLeft) { focusedTag() == "tabHome" || focusedTag() == tab }
        pressUntil(Key.DirectionRight) { focusedTag() == tab }
        press(Key.DirectionCenter)
    }

    private fun live(): LiveUiState = viewModel.live.value!!

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun onATvUpReachesMyListTheChoiceChangesAndBackGoesToTheTopBar() {
        start(favourites(worldReport, stadiumOne), television = true)
        openTabWithRemote("tabLive")
        // Lands on My List, the remote on its first channel.
        waitFor { exists("live") && focusedText().contains(worldReport.name) }
        compose.onNodeWithTag("liveMyList").assertIsSelected()
        // Up from the channels: the choice shown (My List), not the guide or Categories above the list's left edge.
        pressUntil(Key.DirectionUp) { !focusedText().contains(worldReport.name) }
        assertEquals("Up from the channels", "liveMyList", focusedTag())
        // Right: All; OK shows all channels, the remote staying on the choice.
        press(Key.DirectionRight)
        assertEquals("liveAll", focusedTag())
        press(Key.DirectionCenter)
        waitFor { !live().showingFavorites && live().channels.size == Fixtures.channels.size }
        compose.onNodeWithTag("liveAll").assertIsSelected()
        compose.onNodeWithTag("liveMyList").assertIsNotSelected()
        // The remote stays on the choice it made, as the channels below change.
        waitFor(3_000) { focusedTag() == "liveAll" }
        // Down into the channels, Up again: back on the choice shown, now All.
        press(Key.DirectionDown)
        assertTrue("on a channel: ${focused()}", focusedTag() != "liveAll" && !focusedTag().startsWith("tab"))
        pressUntil(Key.DirectionUp) { focusedTag() == "liveAll" || focusedTag().startsWith("tab") }
        assertEquals("Up lands on the choice shown", "liveAll", focusedTag())
        // Back, as on every tab: the top bar, on Live TV.
        back()
        waitFor { focusedTag() == "tabLive" }
        // To Home and back: the choice made stays for the session.
        openTabWithRemote("tabHome")
        waitFor { exists("home") }
        openTabWithRemote("tabLive")
        waitFor { exists("live") && !focusedTag().startsWith("tab") }
        compose.onNodeWithTag("liveAll").assertIsSelected()
    }

    @Test
    fun anotherProfileOpensLiveTvOnMyListAgain() {
        start(favourites(worldReport))
        val me = repository.state.value.activeProfile
        compose.onNodeWithTag("tabLive").performClick()
        waitFor { exists("live") && live().showingFavorites }
        compose.onNodeWithTag("liveMyList").assertIsSelected()
        // All channels chosen, then another tab and back: All still.
        compose.onNodeWithTag("liveAll").performClick()
        waitFor { !live().showingFavorites }
        compose.onNodeWithTag("tabHome").performClick()
        waitFor { exists("home") }
        compose.onNodeWithTag("tabLive").performClick()
        waitFor { exists("live") }
        compose.onNodeWithTag("liveAll").assertIsSelected()
        // Another profile, without favourites: all channels, with the hint.
        val guest = compose.runOnIdle { repository.addProfile("Guest", 1, "star", isKids = false, pin = null) }!!
        compose.runOnIdle { viewModel.switchProfile(guest.id) }
        waitFor { repository.state.value.activeProfile.id == guest.id && !live().showingFavorites && exists("liveMyListHint") }
        compose.onNodeWithTag("liveAll").assertIsSelected()
        // Back to the first profile: My List again, not the All chosen before.
        compose.runOnIdle { viewModel.switchProfile(me.id) }
        waitFor { repository.state.value.activeProfile.id == me.id && live().showingFavorites }
        compose.onNodeWithTag("liveMyList").assertIsSelected()
        assertEquals(listOf(worldReport.name), live().channels.map { it.channel.name })
    }

    @Test
    fun theFirstChannelAddedDoesNotMoveThePageFromUnderTheViewer() {
        start(LibraryState())
        compose.onNodeWithTag("tabLive").performClick()
        waitFor { exists("live") && live().channels.isNotEmpty() }
        compose.onNodeWithTag("liveAll").assertIsSelected()
        // Nothing chosen yet (all channels, My List being empty): a long press adds the channel, and the page stays on
        // all channels rather than turning to My List under the finger.
        val first = live().channels.first()
        compose.onAllNodesWithText(first.channel.name).onLast().performTouchInput { longClick() }
        waitFor { repository.state.value.library.favorites.any { it.itemId == first.channel.id } }
        settle()
        assertFalse(live().showingFavorites)
        compose.onNodeWithTag("liveAll").assertIsSelected()
    }
}
