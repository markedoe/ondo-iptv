package com.markedoe.ondo.core.fuzz

import com.markedoe.ondo.core.library.CategoryTags
import kotlin.test.assertEquals
import org.junit.Test

/** Category names come from the provider and are scanned for their country tag on the main thread. */
class CategoryTagsFuzzTest {
    private val long = 1_000_000

    @Test(timeout = 20_000)
    fun categoryTagsStayLinearOnLongNames() {
        for (unit in listOf("|", "(", "[", "«", "A-", "A-B")) {
            CategoryTags.tagOf(unit.repeat(long / unit.length))
        }
        assertEquals("EX-YU", CategoryTags.tagOf("|EX-YU| Filmovi"))
        assertEquals("PT", CategoryTags.tagOf("[[ PT ]] Filmes"))
    }

    /** Tags found as the former implementation found them. */
    @Test(timeout = 30_000)
    fun categoryTagsMatchTheFormerScan() {
        val fuzz = Fuzz(seed = 17)
        val pieces = listOf(
            "|", "[", "]", "(", ")", "{", "<", "«", "»", "┃", "│", " ", "\t", "-", "–", ":", "/", "_", "•", "►",
            "PT", "EX", "YU", "Fr", "de", "4K", "VOD", "HD", "Films", "A", "b", "1", "🇵🇹", "é",
        )
        repeat(30_000) { case ->
            val name = buildString { repeat(1 + fuzz.int(10)) { append(fuzz.pick(pieces)) } }
            assertEquals(Former.tagOf(name), CategoryTags.tagOf(name), "case $case: ${describe(name)}")
        }
    }

    /** `CategoryTags` before it worked by index. */
    private object Former {
        private val openers = setOf("|", "[", "(", "{", "<", "«", "┃", "│")
        private val closers = setOf("|", "]", ")", "}", ">", "»", "┃", "│")
        private val separators = setOf("|", ":", "-", "–", "—", "▎", "│", "┃", "»", "•", "/", "►", "▶", "➤", "_", "]", ")", "}", ">")

        fun tagOf(name: String): String? {
            var rest = name
            repeat(3) {
                val (token, remainder) = leadingToken(rest) ?: return null
                if (token !in CategoryTags.skipped) return token
                rest = remainder
            }
            return null
        }

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
                if (token != token.uppercase() || next !in separators) return null
            }
            after = after.trimStart { it.isWhitespace() || it.toString() in separators }
            if (after.isEmpty()) return null
            return token.uppercase() to after
        }

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
}
