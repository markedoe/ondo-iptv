package com.markedoe.ondo.player

import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.view.children
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.markedoe.ondo.OndoApplication
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
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
 * The player's controls on a TV, with the remote only (TV-DP): OK, the arrows and Back move through large buttons
 * drawn for the remote, the Subtitles and Audio panels open from them and close with Back, and the controls go away
 * by themselves while the video plays. The movie's server answers nothing: the player waits, without an error.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OndoApplication::class, qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvPlayerRemoteTest {
    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<PlayerActivity>
    private val app: OndoApplication = ApplicationProvider.getApplicationContext()
    private val server = SilentServer()

    @Before
    fun open() {
        val movie = PlayRequest(title = "Northern Lights", subtitle = "2025", urls = listOf("http://127.0.0.1:${server.port}/1.mp4"), isLive = false)
        scenario = ActivityScenario.launch(PlayerActivity.intent(app, movie))
        compose.waitForIdle()
    }

    @After
    fun close() {
        scenario.close()
        server.close()
    }

    /** The player's Media3 view, inside the Compose screen. */
    private fun playerView(activity: PlayerActivity): PlayerView =
        generateSequence(listOf<View>(activity.window.decorView)) { level ->
            level.flatMap { (it as? ViewGroup)?.children?.toList().orEmpty() }.takeIf { it.isNotEmpty() }
        }.flatten().filterIsInstance<PlayerView>().first()

    private fun player(): Player {
        var found: Player? = null
        scenario.onActivity { found = playerView(it).player }
        return requireNotNull(found)
    }

    /** A key of the remote, as the system hands it to the player: down, then up. */
    private fun press(keyCode: Int) {
        scenario.onActivity { activity ->
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        settle()
    }

    /** The main thread's tasks and the frames of [ms] milliseconds. */
    private fun settle(ms: Long = 50) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        compose.waitForIdle()
    }

    private fun back() {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        settle()
    }

    private fun controlsShown(): Boolean = compose.onAllNodes(androidx.compose.ui.test.hasTestTag("tvPlayerControls")).fetchSemanticsNodes().isNotEmpty()

    private fun showControls() {
        press(KeyEvent.KEYCODE_INFO)
        assertTrue("the controls show", controlsShown())
    }

    @Test
    fun theControlsOpenOnPlayPauseAndTheRemoteReachesSubtitlesAndAudio() {
        showControls()
        compose.onNodeWithTag("tvPlayPause").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("tvSkipForward").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("tvSubtitles").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("tvTrackPanel").assertExists()
        // No subtitles in this stream: Off, ticked, has the focus.
        compose.onNodeWithTag("trackOff").assertIsFocused()
        back()
        compose.onNodeWithTag("tvTrackPanel").assertDoesNotExist()
        compose.onNodeWithTag("tvSubtitles").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("tvAudio").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("tvTrackPanel").assertExists()
    }

    @Test
    fun backClosesThePanelThenTheControlsThenThePlayer() {
        showControls()
        repeat(2) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("tvTrackPanel").assertExists()
        back()
        compose.onNodeWithTag("tvTrackPanel").assertDoesNotExist()
        assertTrue("the controls stay", controlsShown())
        back()
        assertFalse("then the controls hide", controlsShown())
        scenario.onActivity { assertFalse("the player stays", it.isFinishing) }
        back()
        scenario.onActivity { assertTrue("then Back leaves", it.isFinishing) }
    }

    @Test
    fun theControlsGoAwayByThemselvesWhilePlayingAndStayWhilePaused() {
        showControls()
        settle(TvControls.TIMEOUT_MS - 1_000)
        assertTrue("still shown before the timeout", controlsShown())
        // A key starts the wait again.
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        settle(TvControls.TIMEOUT_MS - 1_000)
        assertTrue("a key keeps them", controlsShown())
        settle(1_500)
        assertFalse("they go away", controlsShown())
        // Paused (OK with the controls hidden), they stay.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertFalse(player().playWhenReady)
        settle(TvControls.TIMEOUT_MS * 2)
        assertTrue("paused, they stay", controlsShown())
    }

    @Test
    fun okWithTheControlsHiddenPausesAndResumesAndShowsTheControls() {
        assertFalse("no controls at the start", controlsShown())
        assertTrue(player().playWhenReady)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertFalse("OK pauses", player().playWhenReady)
        assertTrue("and shows the controls", controlsShown())
        // The key's release does not press the button that just took the focus.
        compose.onNodeWithTag("tvPlayPause").assertIsFocused()
        back()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue("OK again resumes", player().playWhenReady)
        // With the controls shown, OK presses the focused button: Play/Pause.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertFalse(player().playWhenReady)
        assertEquals(Player.STATE_BUFFERING, player().playbackState)
    }
}

/** A server that takes connections and never answers: the player waits for the stream, without an error. */
class SilentServer : AutoCloseable {
    private val socket = ServerSocket(0)
    private val clients = mutableListOf<Socket>()
    val port: Int get() = socket.localPort

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                synchronized(clients) { clients += client }
            }
        }
    }

    override fun close() {
        socket.close()
        synchronized(clients) { clients.forEach { runCatching { it.close() } } }
    }
}
