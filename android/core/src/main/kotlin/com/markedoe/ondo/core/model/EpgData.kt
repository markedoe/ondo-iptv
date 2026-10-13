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
