package com.markedoe.ondo.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The player's remote keys (Google's TV app quality: TV-PC, TV-PP) and the channel number typed figure by figure. */
class RemoteKeysTest {
    private fun hidden(keyCode: Int, live: Boolean = false, seekable: Boolean = true) =
        RemoteKeys.action(keyCode, controlsShown = false, zapping = live, seekable = seekable)

    private fun shown(keyCode: Int, live: Boolean = false) = RemoteKeys.action(keyCode, controlsShown = true, zapping = live, seekable = true)

    @Test
    fun withTheControlsHiddenOkPausesAndTheArrowsSeek() {
        assertEquals(RemoteAction.TogglePlay, hidden(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(RemoteAction.TogglePlay, hidden(KeyEvent.KEYCODE_ENTER))
        assertEquals(RemoteAction.SeekBack, hidden(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(RemoteAction.SeekForward, hidden(KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    @Test
    fun aStreamThatCannotSeekShowsTheControlsInstead() {
        assertEquals(RemoteAction.ShowControls, hidden(KeyEvent.KEYCODE_DPAD_LEFT, seekable = false))
        assertNull(hidden(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, seekable = false))
    }

    @Test
    fun upAndDownChangeChannelOnLiveTvAndShowTheControlsOnAMovie() {
        assertEquals(RemoteAction.NextChannel, hidden(KeyEvent.KEYCODE_DPAD_UP, live = true))
        assertEquals(RemoteAction.PreviousChannel, hidden(KeyEvent.KEYCODE_DPAD_DOWN, live = true))
        assertEquals(RemoteAction.ShowControls, hidden(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(RemoteAction.ShowControls, hidden(KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test
    fun channelKeysFiguresAndLastChannelWorkWithTheControlsShownToo() {
        assertEquals(RemoteAction.NextChannel, shown(KeyEvent.KEYCODE_CHANNEL_UP, live = true))
        assertEquals(RemoteAction.PreviousChannel, shown(KeyEvent.KEYCODE_CHANNEL_DOWN, live = true))
        assertEquals(RemoteAction.LastChannel, shown(KeyEvent.KEYCODE_LAST_CHANNEL, live = true))
        assertEquals(RemoteAction.Digit(7), shown(KeyEvent.KEYCODE_7, live = true))
        assertEquals(RemoteAction.Digit(3), hidden(KeyEvent.KEYCODE_NUMPAD_3, live = true))
        // A movie has no channel to change to.
        assertNull(hidden(KeyEvent.KEYCODE_CHANNEL_UP))
        assertNull(hidden(KeyEvent.KEYCODE_5))
    }

    @Test
    fun withTheControlsShownTheArrowsMoveThroughThem() {
        assertNull(shown(KeyEvent.KEYCODE_DPAD_CENTER))
        assertNull(shown(KeyEvent.KEYCODE_DPAD_LEFT))
        assertNull(shown(KeyEvent.KEYCODE_DPAD_UP, live = true))
        // Back goes through the system's back (PlayerRemoteTest), never as a key.
        assertNull(shown(KeyEvent.KEYCODE_BACK))
        assertNull(hidden(KeyEvent.KEYCODE_BACK))
    }

    @Test
    fun mediaAndInfoKeys() {
        assertEquals(RemoteAction.TogglePlay, shown(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(RemoteAction.Play, hidden(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(RemoteAction.Pause, hidden(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals(RemoteAction.SeekBack, shown(KeyEvent.KEYCODE_MEDIA_REWIND))
        assertEquals(RemoteAction.ShowControls, hidden(KeyEvent.KEYCODE_INFO))
        assertEquals(RemoteAction.HideControls, shown(KeyEvent.KEYCODE_INFO))
    }

    @Test
    fun theRemotesSubtitlesAndAudioKeysOpenTheirPanel() {
        assertEquals(RemoteAction.Subtitles, hidden(KeyEvent.KEYCODE_CAPTIONS))
        assertEquals(RemoteAction.Subtitles, shown(KeyEvent.KEYCODE_CAPTIONS, live = true))
        assertEquals(RemoteAction.Audio, hidden(KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK, live = true))
        assertEquals(RemoteAction.Audio, shown(KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK))
    }

    @Test
    fun aChannelNumberIsCompleteAfterFourFiguresOrWhenTypingStops() {
        val entry = ChannelNumberEntry()
        assertNull(entry.add(1))
        assertNull(entry.add(2))
        assertEquals("12", entry.typed)
        assertEquals(12, entry.finish())
        assertEquals("", entry.typed)
        listOf(1, 0, 0).forEach { assertNull(entry.add(it)) }
        assertEquals(1004, entry.add(4))
        assertNull(ChannelNumberEntry().apply { add(0) }.finish())
    }
}
