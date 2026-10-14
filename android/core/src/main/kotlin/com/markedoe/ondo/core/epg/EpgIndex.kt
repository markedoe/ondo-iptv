package com.markedoe.ondo.core.epg

import com.markedoe.ondo.core.TextNormalizer
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.EpgData
import com.markedoe.ondo.core.model.EpgProgram
import com.markedoe.ondo.core.model.TimeWindow

/**
 * Fast, immutable guide lookups for playlist channels.
 *
 * Matching order: the user's manual choice ([overrides]), exact `tvg-id`, case-insensitive `tvg-id`, then
 * normalised names (`tvg-name` / channel name vs. XMLTV `display-name` / id).
 */
class EpgIndex private constructor(
    val data: EpgData,
    /** Playlist channel id -> guide channel id chosen by the user (`""` = no guide). */
    val overrides: Map<String, String>,
    private val lookups: Lookups,
) {
    constructor(data: EpgData, overrides: Map<String, String> = emptyMap()) : this(data, overrides, Lookups.build(data))

    /** Index over loose programmes (grouped by [EpgProgram.channelId], sorted by start). */
    constructor(programs: List<EpgProgram>, overrides: Map<String, String> = emptyMap()) : this(
        EpgData(programs = programs.groupBy { it.channelId }.mapValues { (_, list) -> list.sortedBy { it.start } }),
        overrides,
    )

    private class Lookups(val lowercasedIds: Map<String, String>, val nameKeys: Map<String, String>) {
        companion object {
            fun build(data: EpgData): Lookups {
                val lowercased = HashMap<String, String>()
                val nameKeys = HashMap<String, String>()
                for (id in (data.programs.keys + data.channels.keys).sorted()) {
                    lowercased.putIfAbsent(id.lowercase(), id)
                    val idKey = TextNormalizer.channelMatchKey(id)
                    if (idKey.isNotEmpty()) nameKeys.putIfAbsent(idKey, id)
                }
                // Display names take precedence over ids for name matching.
                for (id in data.channels.keys.sorted()) {
                    if (data.programs[id] == null) continue
                    for (name in data.channels.getValue(id).displayNames) {
                        val key = TextNormalizer.channelMatchKey(name)
                        if (key.isNotEmpty()) nameKeys[key] = id
                    }
                }
                return Lookups(lowercased, nameKeys)
            }
        }
    }

    val isEmpty: Boolean
        get() = data.programs.isEmpty()

    /** The same guide with other manual choices. */
    fun withOverrides(overrides: Map<String, String>): EpgIndex = EpgIndex(data, overrides, lookups)

    /** Guide channel id for a playlist channel, if any. */
    fun guideId(channel: Channel): String? {
        overrides[channel.id]?.let { manual -> return manual.ifEmpty { null } }
        return automaticGuideId(channel)
    }

    /** The match found without the user's override. */
    fun automaticGuideId(channel: Channel): String? {
        channel.epgId?.let { id ->
            if (data.programs[id] != null || data.channels[id] != null) return id
            lookups.lowercasedIds[id.lowercase()]?.let { return it }
        }
        for (candidate in listOfNotNull(channel.epgName, channel.name, channel.epgId)) {
            val key = TextNormalizer.channelMatchKey(candidate)
            if (key.isNotEmpty()) lookups.nameKeys[key]?.let { return it }
        }
        return null
    }

    /** Every programme of the channel, sorted by start. */
    fun programs(channel: Channel): List<EpgProgram> {
        val id = guideId(channel) ?: return emptyList()
        return data.programs[id].orEmpty()
    }

    /** Programmes overlapping `[from, to)` (epoch millis). */
    fun programs(channel: Channel, from: Long, to: Long): List<EpgProgram> =
        programs(channel).filter { it.end > from && it.start < to }

    fun programs(channel: Channel, window: TimeWindow): List<EpgProgram> = programs(channel, window.start, window.end)

    /** Logo from the guide, used when the playlist has none. */
    fun iconUrl(channel: Channel): String? {
        val id = guideId(channel) ?: return null
        return data.channels[id]?.iconUrl
    }

    /** Programme airing at `now`. */
    fun current(channel: Channel, now: Long = System.currentTimeMillis()): EpgProgram? {
        val list = programs(channel)
        return indexOfProgram(list, now)?.let { list[it] }
    }

    /** Current programme (or the next one in a gap) followed by the next `count - 1` ones. */
    fun upcoming(channel: Channel, now: Long = System.currentTimeMillis(), count: Int = 2): List<EpgProgram> {
        val list = programs(channel)
        if (list.isEmpty()) return emptyList()
        val start = indexOfProgram(list, now) ?: list.indexOfFirst { it.start > now }.takeIf { it >= 0 } ?: return emptyList()
        return list.subList(start, minOf(start + count, list.size)).toList()
    }

    // Manual matching

    /** A guide channel the user can pick for a playlist channel. */
    data class GuideChannelOption(val id: String, val name: String, val iconUrl: String?, val programCount: Int)

    private val allGuideIds: Set<String>
        get() = data.programs.keys + data.channels.keys

    /**
     * Every channel the guide describes, sorted by name. Channels the playlist did not match have no
     * programmes loaded yet (the loader skips them); they are fetched once picked.
     */
    fun guideChannelOptions(): List<GuideChannelOption> =
        allGuideIds.map(::option).sortedWith(compareBy({ it.name.lowercase() }, { it.id }))

    fun option(guideId: String): GuideChannelOption {
        val channel = data.channels[guideId]
        return GuideChannelOption(
            id = guideId,
            name = channel?.displayNames?.firstOrNull() ?: guideId,
            iconUrl = channel?.iconUrl,
            programCount = data.programs[guideId]?.size ?: 0,
        )
    }

    /** Programme airing on a guide channel (used to preview a guide channel before picking it). */
    fun current(guideId: String, now: Long = System.currentTimeMillis()): EpgProgram? {
        val list = data.programs[guideId] ?: return null
        return indexOfProgram(list, now)?.let { list[it] }
    }

    /** Guide channels whose names look like the playlist channel's, best first. */
    fun suggestions(channel: Channel, limit: Int = 8): List<GuideChannelOption> {
        val wanted = listOfNotNull(channel.name, channel.epgName, channel.epgId).flatMap(::words).toSet()
        if (wanted.isEmpty()) return emptyList()
        val wantedKey = TextNormalizer.channelMatchKey(channel.name)
        val scored = ArrayList<Pair<Double, GuideChannelOption>>()
        for (id in allGuideIds) {
            val names = data.channels[id]?.displayNames.orEmpty() + id
            var best = 0.0
            for (name in names) {
                val nameWords = words(name).toSet()
                if (nameWords.isEmpty()) continue
                var score = (wanted intersect nameWords).size.toDouble() / (wanted union nameWords).size
                val key = TextNormalizer.channelMatchKey(name)
                if (key.isNotEmpty() && wantedKey.isNotEmpty() && (key.startsWith(wantedKey) || wantedKey.startsWith(key))) score += 0.5
                best = maxOf(best, score)
            }
            if (best > 0.2) scored.add(best to option(id))
        }
        return scored
            .sortedWith(compareByDescending<Pair<Double, GuideChannelOption>> { it.first }.thenBy { it.second.name })
            .take(limit)
            .map { it.second }
    }

    // Programme search

    /** A programme found by [searchPrograms], with the guide channel it airs on. */
    data class ProgramMatch(val guideId: String, val program: EpgProgram)

    /** Programmes whose title (or subtitle) contains every word of `query`, within `window`, soonest first. */
    fun searchPrograms(query: String, window: TimeWindow, limit: Int = 200): List<ProgramMatch> {
        val tokens = TextNormalizer.tokens(TextNormalizer.normalize(query))
        if (tokens.isEmpty()) return emptyList()
        val matches = ArrayList<ProgramMatch>()
        for ((id, programs) in data.programs) {
            for (program in programs) {
                if (program.end <= window.start || program.start >= window.end) continue
                val text = TextNormalizer.normalize(program.title + " " + program.subtitle.orEmpty())
                if (TextNormalizer.matches(text, tokens)) matches.add(ProgramMatch(id, program))
            }
        }
        return matches.sortedWith(compareBy({ it.program.start }, { it.guideId })).take(limit)
    }

    /** A new index with programmes fetched on demand (Xtream short EPG) added for a channel. */
    fun inserting(programs: List<EpgProgram>, channel: Channel): EpgIndex {
        val first = programs.firstOrNull() ?: return this
        val id = guideId(channel) ?: first.channelId
        val addition = EpgData(programs = mapOf(id to programs.map { it.copy(channelId = id) }))
        return EpgIndex(data.merged(addition), overrides)
    }

    companion object {
        val EMPTY = EpgIndex(EpgData.EMPTY)

        /** Binary search for the programme airing at `instant` (lists are sorted by start). */
        internal fun indexOfProgram(list: List<EpgProgram>, instant: Long): Int? {
            var low = 0
            var high = list.size - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val program = list[mid]
                when {
                    program.end <= instant -> low = mid + 1
                    program.start > instant -> high = mid - 1
                    else -> return mid
                }
            }
            return null
        }

        /** Words of a channel name without quality / country tags (`"FR: TV1 HD"` -> `["tv1"]`). */
        internal fun words(name: String): List<String> =
            TextNormalizer.tokens(TextNormalizer.normalize(name)).filter { !TextNormalizer.isChannelTag(it) }
    }
}
