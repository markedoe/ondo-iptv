package com.markedoe.ondo.core

import java.text.Normalizer

/** Accent / case / width insensitive text keys used by search and guide matching. */
object TextNormalizer {
    /**
     * Lowercased, accent-free, alphanumerics separated by single spaces.
     * `"Télé-Loisirs  HD"` -> `"tele loisirs hd"`.
     */
    fun normalize(text: String): String {
        val folded = fold(text)
        val result = StringBuilder(folded.length)
        var lastWasSpace = true
        var i = 0
        while (i < folded.length) {
            val codePoint = folded.codePointAt(i)
            i += Character.charCount(codePoint)
            if (isAlphanumeric(codePoint)) {
                result.appendCodePoint(codePoint)
                lastWasSpace = false
            } else if (!lastWasSpace) {
                result.append(' ')
                lastWasSpace = true
            }
        }
        if (result.isNotEmpty() && result[result.length - 1] == ' ') result.setLength(result.length - 1)
        return result.toString()
    }

    /** Lowercase, compatibility decomposition (width folding), combining marks removed. */
    private fun fold(text: String): String {
        val lower = text.lowercase()
        if (lower.all { it.code < 0x80 }) return lower
        val decomposed = Normalizer.normalize(lower.replace("ß", "ss"), Normalizer.Form.NFKD)
        val builder = StringBuilder(decomposed.length)
        var i = 0
        while (i < decomposed.length) {
            val codePoint = decomposed.codePointAt(i)
            i += Character.charCount(codePoint)
            if (Character.getType(codePoint) != Character.NON_SPACING_MARK.toInt()) builder.appendCodePoint(codePoint)
        }
        // Decomposition can expose uppercase compatibility characters (e.g. "℡" -> "TEL").
        return builder.toString().lowercase()
    }

    /** Swift `CharacterSet.alphanumerics`: letters, marks and numbers. */
    private fun isAlphanumeric(codePoint: Int): Boolean {
        if (codePoint < 0x80) {
            return codePoint in 'a'.code..'z'.code || codePoint in '0'.code..'9'.code || codePoint in 'A'.code..'Z'.code
        }
        return when (Character.getType(codePoint).toByte()) {
            Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
            Character.MODIFIER_LETTER, Character.OTHER_LETTER,
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK,
            Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
            else -> false
        }
    }

    private val qualityTokens = setOf(
        "hd", "fhd", "uhd", "sd", "hq", "lq", "4k", "8k", "hevc", "h265", "h264", "1080p", "720p", "2160p",
        "50fps", "60fps", "backup", "raw", "vip", "multi", "plus",
    )

    private val countryPrefixes = setOf(
        "fr", "uk", "us", "usa", "de", "es", "it", "be", "ch", "ca", "nl", "pt", "ar", "tr", "pl", "ro",
        "ma", "dz", "tn", "af", "ex", "int",
    )

    /** Quality or country tag that does not identify a channel ("hd", "fr"…). */
    fun isChannelTag(word: String): Boolean = word in qualityTokens || word in countryPrefixes

    /**
     * Key used to match a playlist channel to a guide channel when ids differ:
     * `"FR: TV1 FHD"` / `"TV1 HD"` / `"tv1"` -> `"tv1"`.
     */
    fun channelMatchKey(name: String): String = channelMatchWords(name).joinToString("")

    /** The words [channelMatchKey] is made of, without a leading country tag or quality words. */
    fun channelMatchWords(name: String): List<String> {
        val tokens = normalize(name).split(' ').filter { it.isNotEmpty() }.toMutableList()
        // Leading "FR", "UK"... country tags ("FR: TV1", "FR | TV1").
        if (tokens.size > 1 && tokens.first() in countryPrefixes) tokens.removeAt(0)
        tokens.removeAll { it in qualityTokens }
        return tokens
    }

    /** Words of an already normalised query, ready for [matches]. */
    fun tokens(normalizedQuery: String): List<String> = normalizedQuery.split(' ').filter { it.isNotEmpty() }

    /** Does `normalizedText` (already normalised) contain every word of the normalised query, by prefix? */
    fun matches(normalizedText: String, queryTokens: List<String>): Boolean {
        if (queryTokens.isEmpty()) return true
        for (token in queryTokens) {
            // Swift: some word starts with the token, or the text contains it. A word prefix is always
            // contained in the text, so the containment test alone decides.
            if (!normalizedText.contains(token)) return false
        }
        return true
    }
}
