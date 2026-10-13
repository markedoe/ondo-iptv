package com.markedoe.ondo.core.model

import kotlinx.serialization.Serializable

/** A time interval `[start, end]` in epoch millis (Foundation `DateInterval`). */
@Serializable
data class TimeWindow(val start: Long, val end: Long) {
    init {
        require(end >= start) { "end ($end) must not be before start ($start)" }
    }

    val durationMillis: Long
        get() = end - start

    operator fun contains(instant: Long): Boolean = instant in start..end

    companion object {
        fun of(start: Long, durationMillis: Long) = TimeWindow(start, start + durationMillis)
    }
}

/** One TV programme from the electronic programme guide. Times are epoch millis. */
@Serializable
data class EpgProgram(
    /** XMLTV channel id the programme belongs to. */
    val channelId: String,
    val start: Long,
    val end: Long,
    val title: String,
    val description: String? = null,
    val subtitle: String? = null,
    val category: String? = null,
    val iconUrl: String? = null,
    /** `"S02E05"` or the provider's on-screen numbering. */
    val episode: String? = null,
    val rating: String? = null,
) {
    /** `channelId|startSeconds`. */
    val id: String
        get() = "$channelId|${start / 1000}"

    val durationMillis: Long
        get() = end - start

    fun isLive(at: Long = System.currentTimeMillis()): Boolean = start <= at && at < end

    fun isPast(at: Long = System.currentTimeMillis()): Boolean = end <= at

    /** 0..1 progress of the programme at `at`. */
    fun progress(at: Long = System.currentTimeMillis()): Double {
        if (durationMillis <= 0) return 0.0
        return ((at - start).toDouble() / durationMillis).coerceIn(0.0, 1.0)
    }
}

/** A `<channel>` entry of an XMLTV file. */
@Serializable
data class EpgChannel(
    val id: String,
    val displayNames: List<String>,
    val iconUrl: String? = null,
)

/** Parsed guide data: channels and their programmes sorted by start. */
@Serializable
data class EpgData(
    val channels: Map<String, EpgChannel> = emptyMap(),
    val programs: Map<String, List<EpgProgram>> = emptyMap(),
    /** Epoch millis (0 = unknown). */
    val fetchedAt: Long = 0L,
) {
    val programCount: Int
        get() = programs.values.sumOf { it.size }

    /** Every programme, channel after channel. */
    val allPrograms: List<EpgProgram>
        get() = programs.values.flatten()

    /**
     * Merges another guide into this one. Programmes overlapping an existing slot are ignored,
     * so the first source added wins (user sources are merged before provider ones).
     */
    fun merged(other: EpgData): EpgData {
        val mergedChannels = LinkedHashMap(channels)
        for ((id, channel) in other.channels) mergedChannels.putIfAbsent(id, channel)
        val mergedPrograms = LinkedHashMap(programs)
        for ((id, incoming) in other.programs) {
            val existing = mergedPrograms[id]
            if (existing.isNullOrEmpty()) {
                mergedPrograms[id] = incoming
                continue
            }
            val combined = existing.toMutableList()
            for (program in incoming) {
                if (existing.none { it.start < program.end && program.start < it.end }) combined.add(program)
            }
            mergedPrograms[id] = combined.sortedBy { it.start }
        }
        return copy(channels = mergedChannels, programs = mergedPrograms)
    }

    /** Drops programmes that ended before `instant` (keeps the archive window small on disk). */
    fun pruned(before: Long): EpgData {
        val kept = LinkedHashMap<String, List<EpgProgram>>()
        for ((id, list) in programs) {
            val remaining = list.filter { it.end >= before }
            if (remaining.isNotEmpty()) kept[id] = remaining
        }
        return copy(programs = kept)
    }

    companion object {
        val EMPTY = EpgData()
    }
}
