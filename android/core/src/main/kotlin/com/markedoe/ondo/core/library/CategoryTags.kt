package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.model.MediaCategory

/**
 * The code many providers put at the start of their category names, most often a country or a language:
 * "|PT| Filmes", "PT | Filmes", "FR: Films", "[DE] Filme", "EN - Movies", "🇵🇹 Filmes". Hiding a tag hides every
 * category that carries it, those the provider adds later included. Same rules as the Swift `CategoryTags`.
 */
object CategoryTags {
    /** Quality and generic words found before the real tag ("4K |FR| Films", "VOD - FR - Action"): skipped. */
    internal val skipped = setOf(
        "4K", "8K", "UHD", "FHD", "HD", "SD", "HEVC", "H265", "H264", "HDR",
        "VOD", "TV", "LIVE", "ALL", "NEW", "TOP", "MOVIES", "MOVIE", "FILMS", "FILM", "SERIES", "SERIE", "SHOWS", "CINEMA",
    )
    private val openers = setOf("|", "[", "(", "{", "<", "«", "┃", "│")
    private val closers = setOf("|", "]", ")", "}", ">", "»", "┃", "│")
    private val separators = setOf("|", ":", "-", "–", "—", "▎", "│", "┃", "»", "•", "/", "►", "▶", "➤", "_", "]", ")", "}", ">")

    /** The tag of a category name, uppercased ("PT"), or null when the name does not start with one. */
    fun tagOf(name: String): String? {
        var rest = name
        // Quality or generic words first, then the tag.
        repeat(3) {
            val (token, remainder) = leadingToken(rest) ?: return null
            if (token !in skipped) return token
            rest = remainder
        }
        return null
    }

    /** Tags carried by at least two of these categories, and the categories that carry each one. */
    fun detect(categories: List<MediaCategory>): Map<String, List<MediaCategory>> =
        categories.mapNotNull { category -> tagOf(category.name)?.let { it to category } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size >= 2 }

    /** One token and what follows it, or null. Works on code points so a flag stays in one piece. */
    private fun leadingToken(text: String): Pair<String, String>? {
        var rest = text.trimStart()
        flagCode(rest)?.let { (code, length) ->
            return code to rest.substring(length).trimStart { it.isWhitespace() || it.toString() in separators }
        }
        var bracketed = false
        while (rest.isNotEmpty() && rest.substring(0, 1) in openers) {
            bracketed = true
            rest = rest.substring(1).trimStart()
        }
        val token = readToken(rest)
        if (token.length !in 2..6 || token.none { it.isLetter() }) return null
        var after = rest.substring(token.length).trimStart()
        val next = after.firstOrNull()?.toString() ?: return null
        if (bracketed) {
            if (next !in closers) return null
        } else {
            // Without brackets, only an upper-case code followed by a separator is a tag ("Action - Drama" is not).
            if (token != token.uppercase() || next !in separators) return null
        }
        after = after.trimStart { it.isWhitespace() || it.toString() in separators }
        if (after.isEmpty()) return null
        return token.uppercase() to after
    }

    /** ASCII letters and digits, with inner hyphens between upper-case parts ("EX-YU"). */
    private fun readToken(text: String): String {
        val token = StringBuilder()
        var index = 0
        while (index < text.length) {
            val character = text[index]
            when {
                character.code < 128 && character.isLetterOrDigit() -> token.append(character)
                character == '-' && token.isNotEmpty() && token.toString() == token.toString().uppercase() &&
                    index + 1 < text.length && text[index + 1].code < 128 && text[index + 1].isUpperCase() -> token.append(character)
                else -> break
            }
            index++
        }
        return token.toString()
    }

    /** "🇵🇹" (two regional indicator symbols) → "PT", with the length of the flag in chars. */
    private fun flagCode(text: String): Pair<String, Int>? {
        if (text.isEmpty()) return null
        val first = text.codePointAt(0)
        if (first !in 0x1F1E6..0x1F1FF) return null
        val firstLength = Character.charCount(first)
        if (text.length <= firstLength) return null
        val second = text.codePointAt(firstLength)
        if (second !in 0x1F1E6..0x1F1FF) return null
        val code = String(charArrayOf('A' + (first - 0x1F1E6), 'A' + (second - 0x1F1E6)))
        return code to firstLength + Character.charCount(second)
    }
}
