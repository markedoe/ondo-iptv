package com.markedoe.ondo.core.model

import kotlinx.serialization.Serializable

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
            val taken = TakenSlots(existing)
            val combined = existing.toMutableList()
            for (program in incoming) {
                if (!taken.overlaps(program)) combined.add(program)
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

/**
 * The time a channel's programmes take, to tell in O(log n) whether another programme overlaps one of them:
 * scanning the list for each programme of a second guide took seconds on long guides.
 */
private class TakenSlots(programs: List<EpgProgram>) {
    private val starts: LongArray

    /** Latest end among the programmes up to each index (programmes can overlap, so ends are not sorted). */
    private val latestEnds: LongArray

    init {
        val sorted = programs.sortedBy { it.start }
        starts = LongArray(sorted.size) { sorted[it].start }
        var latest = Long.MIN_VALUE
        latestEnds = LongArray(sorted.size) { index ->
            latest = maxOf(latest, sorted[index].end)
            latest
        }
    }

    /** Whether one of the programmes starting before `program` ends is still on when it starts. */
    fun overlaps(program: EpgProgram): Boolean {
        var low = 0
        var high = starts.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (starts[mid] < program.end) low = mid + 1 else high = mid
        }
        return low > 0 && latestEnds[low - 1] > program.start
    }
}
