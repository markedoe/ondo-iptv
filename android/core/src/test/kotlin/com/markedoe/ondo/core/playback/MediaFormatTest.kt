package com.markedoe.ondo.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The format shown on movie and series pages. */
class MediaFormatTest {
    @Test
    fun theAddressNamesTheFormat() {
        assertEquals("MKV", MediaFormat.label("http://panel.example.com/movie/u/p/5001.mkv"))
        assertEquals("MP4", MediaFormat.label("http://panel.example.com/movie/u/p/5001.MP4?token=1"))
        assertEquals("HLS", MediaFormat.label("http://panel.example.com/live/u/p/101.m3u8"))
        assertEquals("TS", MediaFormat.label("http://panel.example.com/live/u/p/101.ts"))
        assertNull(MediaFormat.label("http://panel.example.com/play.php?id=5001"), "not a video format")
        assertNull(MediaFormat.label("http://panel.example.com/movie/5001"), "no extension")
    }

    @Test
    fun aSeriesShowsItsFormatOnlyWhenEveryEpisodeSharesIt() {
        val mkv = listOf("http://panel.example.com/series/u/p/1.mkv", "http://panel.example.com/series/u/p/2.mkv")
        assertEquals("MKV", MediaFormat.label(mkv))
        assertNull(MediaFormat.label(mkv + "http://panel.example.com/series/u/p/3.mp4"), "mixed formats")
        assertNull(MediaFormat.label(emptyList()))
    }
}
