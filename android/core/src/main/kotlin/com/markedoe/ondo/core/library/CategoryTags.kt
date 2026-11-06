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

    /** What providers put around a header's or a title's name before its tag ("-----▼", "---●★|"). */
    private const val DECORATION = "-=_~*#•●○◆★☆▼▲►◄▶◀| "

    /**
     * The tag a title's or a channel's own name starts with, by the rules of [tagOf] ("|PT| Tony Carreira", "[PT] …",
     * "PT - …", "PT| …", "🇵🇹 …"), decorations before it left out; null without one. Only a delimited code at the very
     * start counts: "Portugal: The Man" and "PTSD" carry none. Same rules as the Swift `CategoryTags.titleTag`.
     */
    fun titleTag(name: String): String? = tagOf(name.trimStart { it != '|' && it in DECORATION })

    /** Whether a title's or a channel's own [name] starts or ends with one of these hidden tags ([titleTag], [trailingTag]). */
    fun isHiddenByName(name: String, hiddenTags: Set<String>): Boolean =
        hiddenTags.isNotEmpty() && (titleTag(name) in hiddenTags || trailingTag(name) in hiddenTags)

    /** What a tag at the end of a name is closed by, and what opens it. */
    private const val END_CLOSERS = ")]}>|»┃│"
    private const val END_OPENERS = "([{<|«┃│"

    /** What stands between a name and an unbracketed tag at its end ("La Casa - ESP", "La Casa | ES"). */
    private const val END_SEPARATORS = "-–—|"

    /**
     * The tag a title's or a channel's own name ends with, uppercased: a code in brackets ("La Casa (ES)", "El Puente
     * [LAT]", "Os Amigos |PT|") or after a spaced dash or bar ("La Casa - ESP", "La Casa | ES"), years and quality
     * words after it left out ("El Puente [LAT] (2024)", "(LAT) (4K)"); null without one. Conservative: an upper-case code
     * of 2 to 6 letters only, with a name before it; "Quiet Water (2024)", "(4K)", "(Uncut)", "(es)", "Spider-MAN", "La
     * Casa ES" carry none. Same rules as the Swift `CategoryTags.trailingTag`.
     */
    fun trailingTag(name: String): String? {
        var end = endOfText(name, name.length)
        // A year or a quality word ("(2024)", "[HD]") after the tag, at most twice.
        repeat(3) {
            if (end == 0) return null
            val last = name[end - 1]
            val bracketed = last in END_CLOSERS
            val tokenEnd = if (bracketed) endOfText(name, end - 1) else end
            var tokenStart = tokenEnd
            while (tokenStart > 0 && (isAsciiLetterOrDigit(name[tokenStart - 1]) || name[tokenStart - 1] == '-')) tokenStart--
            if (tokenStart == tokenEnd) return null
            val before: Int
            if (bracketed) {
                val opener = endOfText(name, tokenStart)
                if (opener == 0 || name[opener - 1] !in END_OPENERS) return null
                before = opener - 1
            } else {
                // "La Casa - ESP": a space, a dash or a bar, a space.
                if (tokenStart == 0 || !isSpace(name[tokenStart - 1])) return null
                val separator = endOfText(name, tokenStart)
                if (separator < 2 || name[separator - 1] !in END_SEPARATORS || !isSpace(name[separator - 2])) return null
                before = separator - 1
            }
            val token = name.substring(tokenStart, tokenEnd)
            val year = bracketed && token.length == 4 && token.all { it in '0'..'9' }
            if (year || token in skipped) {
                end = endOfText(name, before)
                return@repeat
            }
            if (token.length !in 2..6 || token.first() == '-' || token.last() == '-' || token.none { it in 'A'..'Z' } ||
                token.any { it in 'a'..'z' }
            ) return null
            // A name before the tag: "(ES)" alone is no title in Spanish.
            if ((0 until before).none { isAsciiLetterOrDigit(name[it]) || name[it].code >= 128 }) return null
            return token
        }
        return null
    }

    /** [end] with the spaces before it left out. */
    private fun endOfText(text: String, end: Int): Int {
        var index = end
        while (index > 0 && isSpace(text[index - 1])) index--
        return index
    }

    private fun isSpace(character: Char): Boolean = character == ' ' || character == '\t' || character == '\u00A0'

    private fun isAsciiLetterOrDigit(character: Char): Boolean =
        character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9'

    /**
     * Whether a title is hidden by these hidden tags whatever its category: its own name starts or ends with one
     * ([isHiddenByName]), or its provider's [plot] is in a language one of them stands for ([PlotLanguage]) and its
     * category's own tag ([categoryTag], from [tagOf]) does not name a language: a category tagged with a language the
     * viewer kept ("|FR|") keeps its titles, whatever their description.
     */
    fun isHiddenTitle(
        name: String,
        plot: String?,
        categoryTag: String?,
        hiddenTags: Set<String>,
        hiddenLanguages: Set<String> = PlotLanguage.hiddenLanguages(hiddenTags),
    ): Boolean {
        if (hiddenTags.isEmpty()) return false
        if (isHiddenByName(name, hiddenTags)) return true
        if (hiddenLanguages.isEmpty() || plot.isNullOrEmpty() || !readsPlots(categoryTag)) return false
        return PlotLanguage.detect(plot) in hiddenLanguages
    }

    /** Whether the titles of a category with this tag ([tagOf]) are told by their description's language: no language tag. */
    fun readsPlots(categoryTag: String?): Boolean = categoryTag == null || !PlotLanguage.isLanguageTag(categoryTag)

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
        // By index: cutting the name once per bracket copied it each time.
        var start = 0
        while (start < rest.length && rest[start].toString() in openers) {
            bracketed = true
            start++
            while (start < rest.length && rest[start].isWhitespace()) start++
        }
        rest = rest.substring(start)
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
        // Whether the token has a lower-case letter so far (the token is ASCII: upper-casing changes only those).
        var hasLowercase = false
        var index = 0
        while (index < text.length) {
            val character = text[index]
            when {
                character.code < 128 && character.isLetterOrDigit() -> {
                    token.append(character)
                    if (character in 'a'..'z') hasLowercase = true
                }
                character == '-' && token.isNotEmpty() && !hasLowercase &&
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
