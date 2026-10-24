package com.markedoe.ondo.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A live pause resumes from the archive once it lasted 10 seconds, on a channel with no window to seek in. */
class LivePauseTest {
    private val channel = PlayRequest(title = "World Report", urls = listOf("http://example.com/live.m3u8"), isLive = true, channelId = "c1")

    @Test
    fun onlyALiveChannelWithNoWindowCounts() {
        assertEquals(1_000L, LivePause.pausedAt(channel, seekable = false, now = 1_000))
        assertNull("a window to seek in keeps what aired", LivePause.pausedAt(channel, seekable = true, now = 1_000))
        assertNull("a film", LivePause.pausedAt(channel.copy(isLive = false, channelId = null), seekable = false, now = 1_000))
        assertNull("a replay already", LivePause.pausedAt(channel.copy(isLive = false, replayStart = 500), seekable = false, now = 1_000))
    }

    @Test
    fun aShortPauseResumesAsItIsALongOneWhereItStopped() {
        assertNull(LivePause.resumeFrom(null, 60_000))
        assertNull(LivePause.resumeFrom(1_000, 1_000 + LivePause.THRESHOLD_MS - 1))
        assertEquals(1_000L, LivePause.resumeFrom(1_000, 1_000 + LivePause.THRESHOLD_MS))
    }
}
