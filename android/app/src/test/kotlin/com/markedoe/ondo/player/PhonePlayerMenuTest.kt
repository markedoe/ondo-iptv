package com.markedoe.ondo.player

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.children
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.markedoe.ondo.OndoApplication
import com.markedoe.ondo.data.VideoScale
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The phone player's More sheet: the aspect ratio and the sleep timer, as on a TV. */
@RunWith(RobolectricTestRunner::class)
@Config(application = OndoApplication::class, qualifiers = "w411dp-h891dp-xxhdpi")
class PhonePlayerMenuTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app: OndoApplication = ApplicationProvider.getApplicationContext()
    private lateinit var scenario: ActivityScenario<PlayerActivity>

    private fun launch() {
        val request = PlayRequest(title = "Northern Lights", urls = listOf("http://127.0.0.1:9/film.mp4"), isLive = false)
        scenario = ActivityScenario.launch(PlayerActivity.intent(app, request))
        compose.waitForIdle()
    }

    @After
    fun close() = scenario.close()

    private fun playerView(activity: PlayerActivity): PlayerView =
        generateSequence(listOf<View>(activity.window.decorView)) { level ->
            level.flatMap { (it as? ViewGroup)?.children?.toList().orEmpty() }.takeIf { it.isNotEmpty() }
        }.flatten().filterIsInstance<PlayerView>().first()

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    /** A tap on the video: the controls. */
    private fun showControls() {
        compose.onNodeWithTag("phonePlayer").performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    @Test
    fun moreSetsTheAspectRatio() {
        launch()
        showControls()
        tap("playerMore")
        compose.onNodeWithTag("menuAspect").assertTextContains("Aspect ratio").assertTextContains("Fit")
        tap("menuAspect")
        compose.onNodeWithTag("scale-FIT").assertIsSelected()
        tap("scale-FILL")
        scenario.onActivity { assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, playerView(it).resizeMode) }
        assertEquals(VideoScale.FILL, app.repository.state.value.settings.videoScale)
        showControls()
        tap("playerMore")
        compose.onNodeWithTag("menuAspect").assertTextContains("Fill (crop)")
    }

    @Test
    fun theSleepTimerPausesTheFilm() {
        launch()
        showControls()
        tap("playerMore")
        compose.onNodeWithTag("menuSleep").assertTextContains("Sleep timer").assertTextContains("Off")
        tap("menuSleep")
        tap("sleep-15")
        scenario.onActivity { assertTrue(playerView(it).player!!.playWhenReady) }
        showControls()
        tap("playerMore")
        compose.onNodeWithTag("menuSleep").assertTextContains("Ends at", substring = true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(15).plusSeconds(1))
        compose.waitForIdle()
        scenario.onActivity { assertFalse("paused", playerView(it).player!!.playWhenReady) }
    }
}
