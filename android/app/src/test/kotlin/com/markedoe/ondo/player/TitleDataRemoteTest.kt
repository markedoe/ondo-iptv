package com.markedoe.ondo.player

import android.os.Looper
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.OndoApplication
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.AppStore
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The title data panel in the player on a TV (More, Stream Statistics), for a film of many versions, with the remote
 * only: the controls hidden, Down scrolls it until the last version it lists is on screen, Up back to its top, then Up
 * shows the controls as it does without the panel. The versions' server answers nothing: the player waits, without an
 * error. Invented names and example.com only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OndoApplication::class, qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TitleDataRemoteTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<PlayerActivity>
    private lateinit var app: OndoApplication
    private val server = SilentServer()

    private fun version(id: Int, name: String, extension: String) = Movie(
        id = "v$id", name = name, categoryId = Fixtures.drama.id, year = 2026, containerExtension = extension, xtreamStreamId = id,
        streamUrl = "http://127.0.0.1:${server.port}/$id.$extension",
    )

    private val versions = listOf(
        version(201, "Copper Valley", "mp4"),
        version(202, "Copper Valley 4K DV", "mkv"),
        version(203, "Copper Valley VOSTFR", "mkv"),
        version(204, "Copper Valley MULTI", "mp4"),
        version(205, "Copper Valley 1080p", "mkv"),
    )

    @Before
    fun prepare() {
        app = ApplicationProvider.getApplicationContext()
        val store = AppStore(app.filesDir.resolve("ondo"))
        store.savePlaylists(listOf(Fixtures.playlist))
        store.saveSettings(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID, groupVersions = true))
        store.saveContent(Fixtures.PLAYLIST_ID, Fixtures.content.copy(movies = Fixtures.content.movies + versions, fetchedAt = System.currentTimeMillis()))
        val repository = app.repository
        repository.bootstrap()
        // The saved catalogue is read on a background thread, then handed to the main one.
        val deadline = System.currentTimeMillis() + 10_000
        while (repository.state.value.content.movies.none { it.id == versions.first().id } && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @After
    fun close() {
        scenario.close()
        server.close()
    }

    private fun settle(ms: Long = 50) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        compose.waitForIdle()
    }

    private fun press(keyCode: Int) {
        scenario.onActivity { activity ->
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        settle()
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun focused(tag: String) = runCatching { compose.onNodeWithTag(tag).assertIsFocused() }.isSuccess

    private fun shown(tag: String) = runCatching { compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed() }.isSuccess

    @Test
    fun theRemoteScrollsTheTitleDataToItsLastVersion() {
        scenario = ActivityScenario.launch(PlayerActivity.intent(app, requireNotNull(app.repository.playRequest(versions[1]))))
        settle()
        // More, Stream statistics.
        press(KeyEvent.KEYCODE_INFO)
        repeat(8) { if (!focused("tvMore")) press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        repeat(8) { if (!focused("statisticsToggle")) press(KeyEvent.KEYCODE_DPAD_DOWN) }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        // Back closes More, then hides the controls.
        repeat(4) { if (exists("tvPlayerControls")) scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }.also { settle() } }
        assertFalse("the controls are hidden", exists("tvPlayerControls"))
        // The title data is worked out off the main thread.
        repeat(200) { if (!exists("titleData")) settle().also { Thread.sleep(10) } }
        assertTrue("the title data shows", exists("titleData"))

        val tag = SemanticsProperties.TestTag
        val last = compose.onAllNodes(SemanticsMatcher("a version") { it.config.getOrNull(tag)?.startsWith("titleDataVersion-") == true }, useUnmergedTree = true)
            .fetchSemanticsNodes().mapNotNull { it.config.getOrNull(tag) }.last()
        assertFalse("the last version starts below the screen", shown(last))
        repeat(20) { if (!shown(last)) press(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.onNodeWithTag(last, useUnmergedTree = true).assertIsDisplayed()
        assertFalse("Down scrolled the panel, it did not show the controls", exists("tvPlayerControls"))

        // Back to its top, then Up does what it does without the panel: the controls.
        repeat(30) { if (!exists("tvPlayerControls")) press(KeyEvent.KEYCODE_DPAD_UP) }
        assertTrue("at the top, Up shows the controls", exists("tvPlayerControls"))
        compose.onNodeWithTag(last, useUnmergedTree = true).assertIsNotDisplayed()
    }
}
