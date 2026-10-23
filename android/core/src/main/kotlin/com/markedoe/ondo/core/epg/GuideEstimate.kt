package com.markedoe.ondo.core.epg

import com.markedoe.ondo.core.model.TimeWindow

/**
 * What a guide would keep in memory, by hour of programme start relative to [now], counted while reading it without
 * keeping anything ([XmltvParser.estimateGuide]). A guide larger than the memory it may take is read again within the
 * window [window] picks: around a large panel's thousands of channels, three days of programmes take hundreds of
 * megabytes (measured: about 0.43 KB a programme), more than a TV gives the whole app.
 */
class GuideEstimate internal constructor(private val now: Long, private val bytesByHour: Map<Long, Long>) {
    val totalBytes: Long = bytesByHour.values.sum()

    private fun bytes(hour: Long) = bytesByHour[hour] ?: 0L

    /**
     * The widest window around [now] whose programmes take at most [budget] bytes, within [full]: what is on and what
     * comes next first, two hours ahead for each hour back. Always at least the current hour and the next.
     */
    fun window(budget: Long, full: TimeWindow?): Selection {
        val first = full?.let { Math.floorDiv(it.start - now, HOUR) } ?: (bytesByHour.keys.minOrNull() ?: 0L)
        val last = full?.let { Math.floorDiv(it.end - now, HOUR) } ?: (bytesByHour.keys.maxOrNull() ?: 0L)
        // Hours [from, until) relative to now; a programme that began in the hour before is usually still on.
        var from = 0L
        var until = 2L
        var used = bytes(-1) + bytes(0) + bytes(1)
        while (true) {
            var grew = false
            repeat(2) {
                if (until <= last && used + bytes(until) <= budget) {
                    used += bytes(until)
                    until++
                    grew = true
                }
            }
            if (from > first && used + bytes(from - 2) <= budget) {
                from--
                used += bytes(from - 1)
                grew = true
            }
            if (!grew) break
        }
        val start = now + from * HOUR
        val end = now + until * HOUR
        val window = TimeWindow(full?.let { maxOf(it.start, start) } ?: start, full?.let { minOf(it.end, end) } ?: end)
        return Selection(window, used)
    }

    /** A narrowed window and the bytes its programmes are expected to take. */
    data class Selection(val window: TimeWindow, val bytes: Long)

    internal companion object {
        const val HOUR = 3_600_000L

        /** Memory a kept programme takes: the object, its channel id and its texts (two bytes a character). */
        fun bytesOf(channelId: String, texts: Int): Long = 160L + 2L * (channelId.length + texts)
    }
}
