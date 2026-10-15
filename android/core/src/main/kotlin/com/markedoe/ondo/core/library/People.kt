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
