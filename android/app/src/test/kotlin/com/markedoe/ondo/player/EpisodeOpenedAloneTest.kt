package com.markedoe.ondo.player

import android.os.Looper
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.OndoApplication
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.WatchEntry
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * An episode opened again alone on a TV, after Android stopped the app in the background: the catalogue is read after
 * the player started. Its Episodes row (and Up next) still come once it is. The episode's server answers nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OndoApplication::class, qualifiers = "w960dp-h540dp-land-television-xhdpi")
class EpisodeOpenedAloneTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createEmptyComposeRule()

    private val app: OndoApplication = ApplicationProvider.getApplicationContext()
    private val server = SilentServer()
    private lateinit var scenario: ActivityScenario<PlayerActivity>

    @After
    fun close() {
        scenario.close()
        server.close()
    }

    @Test
    fun theEpisodesRowComesOnceTheCatalogueIsRead() {
        val store = AppStore(app.filesDir.resolve("ondo"))
        store.savePlaylists(listOf(Fixtures.playlist))
        store.saveSettings(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID))
        store.saveContent(Fixtures.PLAYLIST_ID, Fixtures.content.copy(fetchedAt = System.currentTimeMillis()))
        val series = Fixtures.harborLights
        val playing = series.episodes.first { it.season == 1 && it.number == 2 }
        val key = WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.SERIES, series.id, playing.id, title = "", lastWatchedAt = 0).key
        val request = PlayRequest(
            title = series.name,
            subtitle = playing.code,
            urls = listOf("http://127.0.0.1:${server.port}/${playing.id}.mp4"),
            isLive = false,
            progressKey = key,
            kind = "episode",
        )
        scenario = ActivityScenario.launch(PlayerActivity.intent(app, request))
        // The settings, then the catalogue, are read in the background and handed to the main thread.
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val state = app.repository.state.value
            if (state.isReady && !state.isRestoring && state.content.series.isNotEmpty()) break
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        scenario.onActivity { activity ->
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("tvEpisodeRow").assertExists()
        compose.onNodeWithTag("episode-${playing.id}").assertExists()
    }
}
