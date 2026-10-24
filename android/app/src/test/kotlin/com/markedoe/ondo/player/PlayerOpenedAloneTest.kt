package com.markedoe.ondo.player

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.children
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.markedoe.ondo.OndoApplication
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.VideoScale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The player opened again alone, after Android stopped the app in the background: nothing has read the settings yet
 * when it starts. The viewer's languages and aspect ratio still apply.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OndoApplication::class, qualifiers = "w411dp-h891dp-xxhdpi")
class PlayerOpenedAloneTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app: OndoApplication = ApplicationProvider.getApplicationContext()
    private lateinit var scenario: ActivityScenario<PlayerActivity>

    @After
    fun close() = scenario.close()

    private fun playerView(activity: PlayerActivity): PlayerView =
        generateSequence(listOf<View>(activity.window.decorView)) { level ->
            level.flatMap { (it as? ViewGroup)?.children?.toList().orEmpty() }.takeIf { it.isNotEmpty() }
        }.flatten().filterIsInstance<PlayerView>().first()

    @Test
    fun theViewersLanguagesAndAspectRatioStillApply() {
        AppStore(app.filesDir.resolve("ondo")).saveSettings(AppSettings(preferredAudioLanguage = "fr", videoScale = VideoScale.FILL))
        val request = PlayRequest(title = "Northern Lights", urls = listOf("http://127.0.0.1:9/film.mp4"), isLive = false)
        scenario = ActivityScenario.launch(PlayerActivity.intent(app, request))
        // The settings are read in the background, then handed to the main thread.
        val deadline = System.currentTimeMillis() + 10_000
        while (!app.repository.state.value.isReady && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
        compose.waitForIdle()
        scenario.onActivity {
            val player = playerView(it).player!!
            assertEquals("fr", player.trackSelectionParameters.preferredAudioLanguages.firstOrNull())
            assertEquals(VideoScale.FILL.resizeMode, playerView(it).resizeMode)
        }
    }
}
