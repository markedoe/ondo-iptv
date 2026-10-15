package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.TextNormalizer

/** Names in the cast and director fields providers send ("A, B, C", "A / B", "Name (voice)"). */
object People {
    private val separators = Regex("[,;/|\\n]")

    /** A role in brackets is not part of the name. */
    private val role = Regex("\\s*[(\\[][^)\\]]*[)\\]]")

    fun names(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        val seen = HashSet<String>()
        return text.split(separators)
            .map { part -> part.replace(role, "").trim { it.isWhitespace() || it == '.' || it == '…' } }
            .filter { name -> name.length > 1 && name.any(Char::isLetter) && seen.add(TextNormalizer.normalize(name)) }
    }

    /** Whether a cast or director field names this person (accents and case ignored). */
    fun mentions(text: String?, person: String): Boolean {
        val wanted = TextNormalizer.normalize(person)
        return wanted.isNotEmpty() && names(text).any { TextNormalizer.normalize(it) == wanted }
    }
}

/**
 * The actors and directors a catalogue names, for search: series lists give their cast, movie lists do not (a movie's
 * cast is known once its page has been opened). A name met more often ranks higher.
 */
class PeopleIndex(fields: List<String?>) {
    private class Entry(val key: String, val name: String, var count: Int)

    private val entries: List<Entry> = run {
        val byKey = LinkedHashMap<String, Entry>()
        for (field in fields) {
            for (name in People.names(field)) {
                val key = TextNormalizer.normalize(name)
                if (key.isEmpty()) continue
                byKey.getOrPut(key) { Entry(key, name, 0) }.count += 1
            }
        }
        byKey.values.sortedWith(compareByDescending<Entry> { it.count }.thenBy { it.key })
    }

    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * Names in which every word of the query starts a word (accents and case ignored): names that start with the query
     * first, then the others, the most frequent first. Three letters at least.
     */
    fun names(query: String, limit: Int = 20): List<String> {
        val normalized = TextNormalizer.normalize(query)
        val tokens = normalized.split(' ').filter { it.isNotEmpty() }
        if (normalized.length < 3 || tokens.isEmpty()) return emptyList()
        val leading = mutableListOf<String>()
        val others = mutableListOf<String>()
        for (entry in entries) {
            if (leading.size >= limit) break
            val words = entry.key.split(' ')
            if (!tokens.all { token -> words.any { it.startsWith(token) } }) continue
            if (entry.key.startsWith(normalized)) leading += entry.name else others += entry.name
        }
        return (leading + others).take(limit)
    }
}
