package com.markedoe.ondo.core.parsing

import com.markedoe.ondo.core.TextDecoding
import com.markedoe.ondo.core.toStrictDoubleOrNull

/** One subtitle line shown from [start] to [end] (seconds). */
data class SubtitleCue(val start: Double, val end: Double, val text: String)

/** External subtitles (SRT and WebVTT) rendered by the app on top of the video. */
object SubtitleParser {
    fun parse(bytes: ByteArray): List<SubtitleCue> = parse(decode(bytes))

    /**
     * Subtitle files come in UTF-8 or, very often for French and other Western languages, Windows-1252; some editors
     * save UTF-16, always with a byte order mark ([TextDecoding.decode]).
     */
    fun decode(bytes: ByteArray): String = TextDecoding.decode(bytes)

    fun parse(text: String): List<SubtitleCue> {
        val normalized = text
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("﻿", "")
            // A line holding only spaces still separates two cues.
            .split('\n')
            .joinToString("\n") { line -> if (line.all(::isSpaceOrTab)) "" else line }
        val cues = ArrayList<SubtitleCue>()
        for (block in normalized.split("\n\n")) {
            val lines = block.split('\n').filter { it.isNotEmpty() }
            val timingIndex = lines.indexOfFirst { it.contains("-->") }
            if (timingIndex < 0) continue
            val parts = lines[timingIndex].split("-->")
            if (parts.size != 2) continue
            val start = parseTime(parts[0]) ?: continue
            val end = parseTime(parts[1].split(' ').firstOrNull { it.isNotEmpty() }.orEmpty()) ?: continue
            val body = lines.drop(timingIndex + 1).joinToString("\n")
            val clean = stripTags(body).trim()
            if (clean.isEmpty()) continue
            cues.add(SubtitleCue(start, end, clean))
        }
        return cues.sortedBy { it.start }
    }

    /** Swift `CharacterSet.whitespaces`: tab and the space separators. */
    private fun isSpaceOrTab(c: Char): Boolean = c == '\t' || Character.getType(c) == Character.SPACE_SEPARATOR.toInt()

    /** `"00:01:02,500"`, `"00:01:02.500"`, `"01:02.500"` -> seconds. */
    internal fun parseTime(raw: String): Double? {
        val value = raw.trim().replace(',', '.')
        val components = value.split(':').filter { it.isNotEmpty() }
        if (components.size != 2 && components.size != 3) return null
        var seconds = 0.0
        for (component in components) {
            val number = component.toStrictDoubleOrNull() ?: return null
            seconds = seconds * 60 + number
        }
        return seconds
    }

    /** Removes `<i>…</i>` style tags and `{\an8}` style overrides. */
    private fun stripTags(text: String): String {
        val result = StringBuilder(text.length)
        var inTag = false
        for (c in text) {
            when {
                c == '<' || c == '{' -> inTag = true
                c == '>' || c == '}' -> inTag = false
                !inTag -> result.append(c)
            }
        }
        return result.toString()
    }

    /** Cue displayed at `time` seconds (binary search; cues sorted by start). */
    fun cueAt(time: Double, cues: List<SubtitleCue>): SubtitleCue? {
        var low = 0
        var high = cues.size - 1
        var candidate = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (cues[mid].start <= time) {
                candidate = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (candidate < 0) return null
        // Overlapping cues: walk back a little to find one still on screen.
        var i = candidate
        while (i >= 0 && i >= candidate - 3) {
            if (cues[i].start <= time && time < cues[i].end) return cues[i]
            i--
        }
        return null
    }
}
