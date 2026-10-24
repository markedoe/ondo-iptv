package com.markedoe.ondo.player

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import com.markedoe.ondo.data.VideoScale
import com.markedoe.ondo.ui.theme.OndoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The player's controls on a phone, with a finger: a tap shows them, a double tap on a side skips, a finger held plays
 * twice as fast, slides change the brightness and the volume, a pinch fills the screen, the bar moves the video, the
 * lists open in sheets; locked, nothing but the unlock button answers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h360dp-land-xxhdpi")
class PhonePlayerOverlayTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var controls: PlayerControls
    private var scale by mutableStateOf(VideoScale.FIT)

    private class Levels : PlayerLevels {
        private var light = 0.5f
        private var sound = 0.5f
        override val brightness get() = light
        override fun setBrightness(level: Float) {
            light = level
        }
        override val volume get() = sound
        override fun setVolume(level: Float) {
            sound = level
        }
    }

    private val levels = Levels()

    private val movie = FakeTracksPlayer(
        listOf(
            FakeTracksPlayer.audio("en", MimeTypes.AUDIO_AAC, channels = 2),
            FakeTracksPlayer.audio("fr", MimeTypes.AUDIO_AAC, channels = 2),
            FakeTracksPlayer.text("en", label = "English"),
        ),
        initiallySelected = setOf(0),
    )

    private var chosen: String? = null
    private var subtitleSize by mutableStateOf(com.markedoe.ondo.data.SubtitleSize.NORMAL)
    private var restarted = 0
    private var wentLive = 0
    private val channelList = ChannelList(
        load = { listOf(ChannelChoice("c1", 1, "World Report", "Evening News", playing = true), ChannelChoice("c2", 2, "Cycling TV", "Mountain Stage", playing = false)) },
        onChoose = { chosen = it },
    )

    private fun start(player: FakeTracksPlayer = movie, info: PlayerInfo = PlayerInfo("Northern Lights", "2025", live = false), channels: ChannelList? = null) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            controls = remember { PlayerControls(scope) }
            val sleep = remember { SleepTimer(scope) {} }
            OndoTheme {
                PhonePlayerOverlay(
                    player, controls, info, statisticsShown = false, onToggleStatistics = {}, onReportProblem = {},
                    scale = scale, onScale = { scale = it }, sleep = sleep, levels = levels, onClose = {}, onPictureInPicture = {},
                    channels = channels, onRestart = { restarted++ }, onWatchLive = { wentLive++ },
                    subtitles = SubtitleSizing(subtitleSize) { subtitleSize = it },
                )
            }
        }
        compose.waitForIdle()
    }

    /** Past the double tap's wait, and the controls' animations. */
    private fun settle(ms: Long = 600) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun exists(tag: String) = compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aTapShowsTheControlsAndAnotherHidesThem() {
        start()
        assertFalse(exists("phonePlayerControls"))
        compose.onNodeWithTag("phonePlayer").performTouchInput { click(center) }
        settle()
        assertTrue(exists("phonePlayerControls"))
        compose.onNodeWithTag("phonePlayer").performTouchInput { click(Offset(width * 0.5f, height * 0.3f)) }
        settle()
        assertFalse(exists("phonePlayerControls"))
    }

    @Test
    fun aDoubleTapOnASideSkipsTenSecondsThatWay() {
        start()
        compose.onNodeWithTag("phonePlayer").performTouchInput { doubleClick(Offset(width * 0.9f, height / 2f)) }
        settle(100)
        assertTrue(exists("seekFeedback"))
        compose.onNodeWithTag("seekFeedback", useUnmergedTree = true).assertExists()
        settle(1_500)
        assertEquals(listOf(610_000L), movie.seeks)
        compose.onNodeWithTag("phonePlayer").performTouchInput { doubleClick(Offset(width * 0.1f, height / 2f)) }
        settle(1_500)
        assertEquals(listOf(610_000L, 600_000L), movie.seeks)
        // The middle third plays and pauses.
        compose.onNodeWithTag("phonePlayer").performTouchInput { doubleClick(center) }
        settle()
        assertFalse(movie.playWhenReady)
    }

    @Test
    fun aFingerHeldPlaysTwiceAsFastUntilItLifts() {
        start()
        compose.onNodeWithTag("phonePlayer").performTouchInput {
            down(center)
            advanceEventTime(50)
        }
        settle(800)
        assertEquals(2f, movie.playbackParameters.speed)
        assertTrue(exists("speedBoost"))
        compose.onNodeWithTag("phonePlayer").performTouchInput { up() }
        settle()
        assertEquals(1f, movie.playbackParameters.speed)
        assertFalse(exists("speedBoost"))
    }

    @Test
    fun slidesChangeTheBrightnessOnTheLeftAndTheVolumeOnTheRight() {
        start()
        compose.onNodeWithTag("phonePlayer").performTouchInput { swipe(Offset(width * 0.25f, height * 0.75f), Offset(width * 0.25f, height * 0.35f), 300) }
        settle()
        assertTrue("brighter: ${levels.brightness}", levels.brightness > 0.7f)
        assertEquals(0.5f, levels.volume)
        compose.onNodeWithTag("phonePlayer").performTouchInput { swipe(Offset(width * 0.75f, height * 0.35f), Offset(width * 0.75f, height * 0.75f), 300) }
        settle()
        assertTrue("quieter: ${levels.volume}", levels.volume < 0.3f)
    }

    @Test
    fun aPinchFillsTheScreenAndTheOtherWayFitsThePicture() {
        start()
        compose.onNodeWithTag("phonePlayer").performTouchInput {
            pinch(Offset(centerX - 40f, centerY), Offset(centerX - 250f, centerY), Offset(centerX + 40f, centerY), Offset(centerX + 250f, centerY), 300)
        }
        settle()
        assertEquals(VideoScale.FILL, scale)
        compose.onNodeWithTag("phonePlayer").performTouchInput {
            pinch(Offset(centerX - 250f, centerY), Offset(centerX - 40f, centerY), Offset(centerX + 250f, centerY), Offset(centerX + 40f, centerY), 300)
        }
        settle()
        assertEquals(VideoScale.FIT, scale)
        assertTrue("a pinch is not a skip", movie.seeks.isEmpty())
    }

    @Test
    fun lockedTheScreenIgnoresEverythingButTheUnlockButton() {
        start()
        compose.runOnIdle { controls.show() }
        settle()
        compose.onNodeWithTag("playerLock").performClick()
        settle()
        assertFalse(exists("phonePlayerControls"))
        compose.onNodeWithTag("phonePlayer").performTouchInput { doubleClick(Offset(width * 0.9f, height / 2f)) }
        settle(1_500)
        assertTrue("no skip while locked", movie.seeks.isEmpty())
        compose.onNodeWithTag("phonePlayer").performTouchInput { click(center) }
        settle()
        compose.onNodeWithTag("playerUnlock").performClick()
        settle()
        assertTrue(exists("phonePlayerControls"))
    }

    @Test
    fun theBarMovesTheVideoWhereTheFingerLifts() {
        start()
        compose.runOnIdle { controls.show() }
        settle()
        compose.onNodeWithTag("phoneSeekBar").performTouchInput { swipe(Offset(width * 0.1f, centerY), Offset(width * 0.5f, centerY), 400) }
        settle()
        assertEquals("one seek, when the finger lifts", 1, movie.seeks.size)
        val half = 1_800_000L
        assertTrue("near the middle: ${movie.seeks}", kotlin.math.abs(movie.seeks.single() - half) < 60_000)
    }

    @Test
    fun theListsOpenInSheets() {
        start()
        compose.runOnIdle { controls.show() }
        settle()
        compose.onNodeWithTag("phoneAudio").performClick()
        settle()
        compose.onNodeWithTag("track-0").assertIsSelected().assertTextContains("English")
        compose.onNodeWithTag("track-1").assertTextContains("French").performClick()
        settle()
        val audio = movie.trackParameters.overrides.values.single { it.type == C.TRACK_TYPE_AUDIO }
        assertEquals("fr", audio.mediaTrackGroup.getFormat(0).language)
        assertFalse(exists("playerSheet"))
        compose.onNodeWithTag("playerMore").performClick()
        settle()
        compose.onNodeWithTag("menuAspect").assertTextContains("Fit").performClick()
        settle()
        compose.onNodeWithTag("scale-FILL").performClick()
        settle()
        assertEquals(VideoScale.FILL, scale)
    }

    @Test
    fun aLiveChannelShowsLiveAndNoSkips() {
        val channel = FakeTracksPlayer(listOf(FakeTracksPlayer.audio("en", MimeTypes.AUDIO_AAC, channels = 2)), initiallySelected = setOf(0), seekable = false)
        start(channel, PlayerInfo("World Report", "Evening News", live = true))
        compose.runOnIdle { controls.show() }
        settle()
        assertTrue(exists("liveBadge"))
        assertFalse(exists("phoneSkipBack"))
        assertFalse(exists("phoneSeekBar"))
        compose.onNodeWithTag("phonePlayer").performTouchInput { doubleClick(Offset(width * 0.9f, height / 2f)) }
        settle(1_500)
        assertTrue(channel.seeks.isEmpty())
    }

    /** A live channel: its Channels list, the one playing ticked, another one chosen. */
    @Test
    fun theChannelsListZapsToAnother() {
        val channel = FakeTracksPlayer(listOf(FakeTracksPlayer.audio("en", MimeTypes.AUDIO_AAC, channels = 2)), initiallySelected = setOf(0), seekable = false)
        start(channel, PlayerInfo("World Report", "Evening News", live = true), channelList)
        compose.runOnIdle { controls.show() }
        settle()
        compose.onNodeWithTag("phoneChannels").performClick()
        settle()
        compose.onNodeWithTag("channel-c1").assertIsSelected().assertTextContains("1  World Report").assertTextContains("Evening News")
        compose.onNodeWithTag("channel-c2").assertTextContains("Mountain Stage").performClick()
        settle()
        assertEquals("c2", chosen)
        assertFalse(exists("playerSheet"))
    }

    @Test
    fun aFilmHasNoChannelsList() {
        start()
        compose.runOnIdle { controls.show() }
        settle()
        assertFalse(exists("phoneChannels"))
    }

    /** A live channel with the provider's archive: Restart plays its programme from the start. */
    @Test
    fun aLiveChannelWithAnArchiveRestartsItsProgramme() {
        val channel = FakeTracksPlayer(listOf(FakeTracksPlayer.audio("en", MimeTypes.AUDIO_AAC, channels = 2)), initiallySelected = setOf(0), seekable = false)
        start(channel, PlayerInfo("World Report", "Evening News", live = true, canRestart = true), channelList)
        compose.runOnIdle { controls.show() }
        settle()
        compose.onNodeWithTag("phoneRestart").assertTextContains("Restart").performClick()
        settle()
        assertEquals(1, restarted)
        assertFalse("live, not a replay", exists("replayBadge"))
    }

    @Test
    fun aLiveChannelWithoutAnArchiveHasNoRestart() {
        val channel = FakeTracksPlayer(listOf(FakeTracksPlayer.audio("en", MimeTypes.AUDIO_AAC, channels = 2)), initiallySelected = setOf(0), seekable = false)
        start(channel, PlayerInfo("World Report", "Evening News", live = true), channelList)
        compose.runOnIdle { controls.show() }
        settle()
        assertFalse(exists("phoneRestart"))
        assertFalse(exists("phoneWatchLive"))
    }

    /** A replay plays as a film, said so, with Back to live; its channel's list stays. */
    @Test
    fun aReplaySaysSoAndGoesBackToLive() {
        start(info = PlayerInfo("1  World Report", "Morning Show", live = false, replay = true), channels = channelList)
        compose.runOnIdle { controls.show() }
        settle()
        assertTrue(exists("replayBadge"))
        assertTrue("a replay seeks", exists("phoneSeekBar"))
        assertTrue(exists("phoneChannels"))
        assertFalse(exists("phoneRestart"))
        compose.onNodeWithTag("phoneWatchLive").assertTextContains("Back to live").performClick()
        settle()
        assertEquals(1, wentLive)
    }

    @Test
    fun moreChangesTheSubtitleSize() {
        start()
        compose.runOnIdle { controls.show() }
        settle()
        compose.onNodeWithTag("playerMore").performClick()
        settle()
        compose.onNodeWithTag("menuSubtitleSize").assertTextContains("Subtitle size").assertTextContains("Normal").performClick()
        settle()
        compose.onNodeWithTag("size-NORMAL").assertIsSelected()
        compose.onNodeWithTag("size-EXTRA_LARGE").assertTextContains("Extra large").performClick()
        settle()
        assertEquals(com.markedoe.ondo.data.SubtitleSize.EXTRA_LARGE, subtitleSize)
        assertFalse(exists("playerSheet"))
    }

    @Test
    fun aFilmIsNoReplay() {
        start()
        compose.runOnIdle { controls.show() }
        settle()
        assertFalse(exists("replayBadge"))
        assertFalse(exists("phoneWatchLive"))
        assertFalse(exists("phoneRestart"))
    }
}
