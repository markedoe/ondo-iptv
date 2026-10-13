package com.markedoe.ondo.core.epg

import com.markedoe.ondo.core.model.TimeWindow

/** Restricts what is kept from a (potentially huge) XMLTV guide. */
class EpgFilter(
    /** Keep programmes overlapping this window. `null` keeps everything. */
    val window: TimeWindow? = null,
    channelIds: Set<String>? = null,
    /** `TextNormalizer.channelMatchKey` of playlist channel names, used when ids do not match. */
    val channelNameKeys: Set<String>? = null,
) {
    /** Guide ids referenced by the playlist (`tvg-id`), lowercased: they are compared case-insensitively. */
    val channelIds: Set<String>? = channelIds?.mapTo(HashSet()) { it.lowercase() }

    internal val filtersChannels: Boolean
        get() = channelIds != null || channelNameKeys != null

    companion object {
        val NONE = EpgFilter()

        /** Default window: archive days back, three days ahead. */
        fun standard(now: Long = System.currentTimeMillis(), daysBack: Int = 7, daysAhead: Int = 3): TimeWindow =
            TimeWindow(now - daysBack * 86_400_000L, now + daysAhead * 86_400_000L)
    }
}
