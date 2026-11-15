package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.CoreJson
import com.markedoe.ondo.core.model.SpecSource
import com.markedoe.ondo.core.model.StreamSpecs
import kotlinx.serialization.Serializable

/**
 * What the player measured of the streams it played ([StreamSpecs], [SpecSource.MEASURED]), by [key] (a playlist and a
 * stream id): the [capacity] streams played last, the oldest forgotten first. Kept on the device as one small document
 * ([encode], [decode]). Thread-safe. The same rules as Apple's `MeasuredSpecs` (IPTVCore).
 */
class MeasuredSpecs(val capacity: Int = CAPACITY, entries: List<Entry> = emptyList()) {
    /** One stream's specs, for the document. */
    @Serializable
    data class Entry(val key: String, val specs: StreamSpecs)

    @Serializable
    private data class Document(val entries: List<Entry> = emptyList())

    /** Oldest first: a stream played again moves to the end. */
    private val map = LinkedHashMap<String, StreamSpecs>()

    init {
        for (entry in entries) put(entry.key, entry.specs)
    }

    @Synchronized
    operator fun get(key: String): StreamSpecs? = map[key]

    @Synchronized
    fun size(): Int = map.size

    /**
     * Keeps what the player measured of [key]'s stream: false (nothing to save) when it is what was kept already, or
     * nothing is known. A field the player did not read this time keeps what it read before ([StreamSpecs.orElse]).
     */
    @Synchronized
    fun record(key: String, specs: StreamSpecs): Boolean {
        if (specs.isEmpty) return false
        val measured = specs.copy(source = SpecSource.MEASURED).orElse(map[key])
        if (map[key] == measured) {
            // Played again: kept a while longer.
            map.remove(key)
            map[key] = measured
            return false
        }
        put(key, measured)
        return true
    }

    private fun put(key: String, specs: StreamSpecs) {
        map.remove(key)
        map[key] = specs.copy(source = SpecSource.MEASURED)
        while (map.size > capacity) map.remove(map.keys.first())
    }

    /** Every stream's specs by key, oldest first. */
    @Synchronized
    fun snapshot(): Map<String, StreamSpecs> = LinkedHashMap(map)

    /** Every stream's specs, oldest first: what a document keeps. */
    @Synchronized
    fun entries(): List<Entry> = map.map { (key, specs) -> Entry(key, specs) }

    @Synchronized
    fun encode(): String = CoreJson.encodeToString(Document.serializer(), Document(map.map { (key, specs) -> Entry(key, specs) }))

    companion object {
        /** Streams kept at most: a few hundred bytes each. */
        const val CAPACITY = 2000

        /** The key of [streamId] in [playlistId]: ids of two playlists can be the same. */
        fun key(playlistId: String, streamId: String): String = "$playlistId|$streamId"

        /** The document [encode] wrote; empty when it cannot be read. */
        fun decode(text: String?, capacity: Int = CAPACITY): MeasuredSpecs {
            val entries = text?.let { runCatching { CoreJson.decodeFromString(Document.serializer(), it).entries }.getOrNull() }.orEmpty()
            return MeasuredSpecs(capacity, entries)
        }
    }
}
