package com.markedoe.ondo.player

import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.view.children
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.OndoApplication
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
 * The remote in the player on a live channel: CH+ goes to the next channel, a typed number to that channel, "last
 * channel" back to the one before, a number that matches nothing says so. The streams point nowhere (a closed local
 * port): changing channel works whether a stream plays or failed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OndoApplication::class, qualifiers = "w960dp-h540dp-land-television-xhdpi")
class PlayerRemoteTest {
    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<PlayerActivity>
    private lateinit var app: OndoApplication

    @Before
    fun prepare() {
        app = ApplicationProvider.getApplicationContext()
        val store = AppStore(app.filesDir.resolve("ondo"))
        val content = Fixtures.content.copy(
            channels = Fixtures.content.channels.map { it.copy(streamUrl = "http://127.0.0.1:9/${it.id}.m3u8") },
            movies = Fixtures.content.movies.map { it.copy(streamUrl = "http://127.0.0.1:9/${it.id}.mp4") },
            fetchedAt = System.currentTimeMillis(),
        )
        store.savePlaylists(listOf(Fixtures.playlist))
        store.saveSettings(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID))
        store.saveContent(Fixtures.PLAYLIST_ID, content)
        val repository = app.repository
        repository.bootstrap()
        // The saved catalogue is read on a background thread, then handed to the main one.
        val deadline = System.currentTimeMillis() + 10_000
        while (repository.state.value.content.channels.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun launch(request: PlayRequest?) {
        scenario = ActivityScenario.launch(PlayerActivity.intent(app, requireNotNull(request)))
        compose.waitForIdle()
    }

    private fun launchChannel() = launch(app.repository.playRequest(app.repository.state.value.content.channels.first { it.id == "c1" }))

    @After
    fun close() = scenario.close()

    /** The player's Media3 view, inside the Compose screen. */
    private fun playerView(activity: PlayerActivity): PlayerView =
        generateSequence(listOf<View>(activity.window.decorView)) { level ->
            level.flatMap { (it as? ViewGroup)?.children?.toList().orEmpty() }.takeIf { it.isNotEmpty() }
        }.flatten().filterIsInstance<PlayerView>().first()

    private fun press(keyCode: Int) {
        scenario.onActivity { activity ->
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        compose.waitForIdle()
    }

    private fun banner(text: String) = compose.onNodeWithTag("channelBanner").assertTextContains(text, substring = true)

    @Test
    fun channelKeysNumbersAndLastChannel() {
        launchChannel()
        press(KeyEvent.KEYCODE_CHANNEL_UP)
        banner("2  World Report")
        // A typed number: its figures on screen, the channel once the remote stops typing.
        press(KeyEvent.KEYCODE_4)
        compose.onNodeWithTag("typedNumber").assertTextContains("4")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ChannelNumberEntry.WAIT_MS + 100))
        compose.waitForIdle()
        banner("4  Cycling TV")
        press(KeyEvent.KEYCODE_LAST_CHANNEL)
        banner("World Report")
        press(KeyEvent.KEYCODE_9)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ChannelNumberEntry.WAIT_MS + 100))
        compose.waitForIdle()
        banner("No channel 9")
    }

    @Test
    fun onAMovieAnArrowShowsTheControlsAndBackHidesThemBeforeLeaving() {
        launch(app.repository.playRequest(app.repository.state.value.content.movies.first()))
        scenario.onActivity { playerView(it).hideController() }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        scenario.onActivity { assertTrue("the controls show", playerView(it).isControllerFullyVisible) }
        // The system's back (a remote's Back from Android 13): the controls first, then the player.
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        scenario.onActivity {
            assertFalse("the controls are hidden", playerView(it).isControllerFullyVisible)
            assertFalse("the player stays", it.isFinishing)
            it.onBackPressedDispatcher.onBackPressed()
            assertTrue("then Back leaves", it.isFinishing)
        }
    }
}
