package com.markedoe.ondo.player

import android.os.Looper
import android.view.KeyEvent
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.OndoApplication
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.AppStore
import java.time.Duration
import org.junit.After
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

    @Before
    fun start() {
        val app = ApplicationProvider.getApplicationContext<OndoApplication>()
        val store = AppStore(app.filesDir.resolve("ondo"))
        val channels = Fixtures.content.channels.map { it.copy(streamUrl = "http://127.0.0.1:9/${it.id}.m3u8") }
        store.savePlaylists(listOf(Fixtures.playlist))
        store.saveSettings(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID))
        store.saveContent(Fixtures.PLAYLIST_ID, Fixtures.content.copy(channels = channels, fetchedAt = System.currentTimeMillis()))
        val repository = app.repository
        repository.bootstrap()
        // The saved catalogue is read on a background thread, then handed to the main one.
        val deadline = System.currentTimeMillis() + 10_000
        while (repository.state.value.content.channels.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
        val request = requireNotNull(repository.playRequest(repository.state.value.content.channels.first { it.id == "c1" }))
        scenario = ActivityScenario.launch(PlayerActivity.intent(app, request))
        compose.waitForIdle()
    }

    @After
    fun close() = scenario.close()

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
}
