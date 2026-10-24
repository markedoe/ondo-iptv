package com.markedoe.ondo.player

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.markedoe.ondo.OndoApplication
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * The player's media session while another player opens. Android stops and destroys a closed screen only once the
 * next one is shown: a player opened at once after another was closed (a title whose episodes were still loading
 * starts as the app comes back) is created while the closed one still holds its session.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = OndoApplication::class, qualifiers = "w411dp-h891dp-xxhdpi")
class PlayerSessionTest {
    private val app: OndoApplication = ApplicationProvider.getApplicationContext()

    private fun request(title: String) = PlayRequest(title = title, urls = listOf("http://127.0.0.1:9/$title.mp4"), isLive = false)

    @Test
    fun aPlayerOpenedBeforeTheClosedOneIsDestroyedHasItsOwnSession() {
        val closed = Robolectric.buildActivity(PlayerActivity::class.java, PlayerActivity.intent(app, request("first"))).setup()
        closed.get().finish()
        closed.pause()
        val opened = Robolectric.buildActivity(PlayerActivity::class.java, PlayerActivity.intent(app, request("second"))).setup()
        assertTrue(opened.get().hasMediaSession)
        closed.stop().destroy()
        assertTrue("the new player keeps its session", opened.get().hasMediaSession)
        opened.pause().stop().destroy()
    }
}
