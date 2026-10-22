package com.markedoe.ondo.core.parsing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitleTest {
    @Test
    fun srt() {
        val srt = "﻿1\r\n00:00:01,000 --> 00:00:02,500\r\n<i>Hello</i> there\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nSecond\r\nline two\r\n\r\n3\r\nbroken timing\r\ntext\r\n"
        val cues = SubtitleParser.parse(srt)
        assertEquals(2, cues.size)
        assertEquals(SubtitleCue(1.0, 2.5, "Hello there"), cues[0])
        assertEquals("Second\nline two", cues[1].text)
        assertEquals("Hello there", SubtitleParser.cueAt(1.2, cues)?.text)
        assertNull(SubtitleParser.cueAt(2.7, cues))
        assertEquals("Second\nline two", SubtitleParser.cueAt(3.5, cues)?.text)
        assertNull(SubtitleParser.cueAt(0.5, cues))
    }

    /**
     * Sloppy files separate cues with lines holding spaces or tabs: the next cue's number and timing must not
     * end up in the previous cue's text.
     */
    @Test
    fun blankLinesHoldingSpacesStillSeparateCues() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nFirst\n \n2\n00:00:03,000 --> 00:00:04,000\nSecond\n\t\n3\n00:00:05,000 --> 00:00:06,000\nThird\n"
        assertEquals(listOf("First", "Second", "Third"), SubtitleParser.parse(srt).map { it.text })
    }

    @Test
    fun utf16SubtitlesWithAByteOrderMarkAreDecoded() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nL’été\n"
        val little = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val big = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)
        assertEquals("L’été", SubtitleParser.parse(little).firstOrNull()?.text)
        assertEquals("L’été", SubtitleParser.parse(big).firstOrNull()?.text)
    }

    /** Old French subtitles are often Windows-1252: its curly apostrophes and "œ" are not control characters. */
    @Test
    fun windows1252SubtitlesKeepTheirPunctuation() {
        // Bytes 0x92, 0xE9, 0x9C, 0x85 and 0x81 (undefined in Windows-1252).
        val bytes = "1\n00:00:01,000 --> 00:00:02,000\nL\u0092été \u009Cuvre\u0085\u0081\n".toByteArray(Charsets.ISO_8859_1)
        assertEquals("L’été œuvre…\uFFFD", SubtitleParser.parse(bytes).firstOrNull()?.text)
    }

    @Test
    fun webVtt() {
        val vtt = "WEBVTT\n\nintro\n00:01.000 --> 00:02.000 align:start\n{\\an8}Short <b>form</b>\n\n01:00:00.000 --> 01:00:01.000\nHour\n"
        val cues = SubtitleParser.parse(vtt)
        assertEquals(listOf(1.0, 3600.0), cues.map { it.start })
        assertEquals("Short form", cues[0].text)
    }

    @Test
    fun latin1FallbackAndOverlaps() {
        val latin1 = "1\n00:00:01,000 --> 00:00:05,000\nCafé\n\n2\n00:00:02,000 --> 00:00:03,000\nOverlap\n".toByteArray(Charsets.ISO_8859_1)
        val cues = SubtitleParser.parse(latin1)
        assertEquals("Café", cues[0].text)
        assertEquals("Café", SubtitleParser.cueAt(4.0, cues)?.text, "an earlier, longer cue is still on screen")
        assertEquals("Overlap", SubtitleParser.cueAt(2.5, cues)?.text)
        assertEquals("Café", SubtitleParser.parse("1\n00:00:01,000 --> 00:00:05,000\nCafé\n".encodeToByteArray())[0].text)
    }
}
