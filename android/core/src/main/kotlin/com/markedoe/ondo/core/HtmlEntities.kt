package com.markedoe.ondo.core

import java.text.Normalizer

/**
 * The HTML character references some providers leave in their text ("Action &amp; Adventure", "Com&eacute;die",
 * "L&#39;Odyssée"): `&amp;`, `&lt;`, `&gt;`, `&quot;`, `&apos;`, `&nbsp;`, the accented Latin letters (`&eacute;`…),
 * a few marks of punctuation, and numeric ones (`&#39;`, `&#x27;`). Text escaped twice (`&amp;amp;`) is decoded again,
 * up to [PASSES] times. A reference this does not know stays as it is. The same rules as the Swift `HtmlEntities`.
 */
object HtmlEntities {
    private const val PASSES = 3

    /** The longest name looked at after an `&`. */
    private const val LONGEST = 10

    private val named: Map<String, String> = buildMap {
        put("amp", "&")
        put("lt", "<")
        put("gt", ">")
        put("quot", "\"")
        put("apos", "'")
        put("nbsp", " ")
        put("szlig", "ß")
        put("aelig", "æ")
        put("AElig", "Æ")
        put("oelig", "œ")
        put("OElig", "Œ")
        put("oslash", "ø")
        put("Oslash", "Ø")
        put("hellip", "…")
        put("lsquo", "‘")
        put("rsquo", "’")
        put("ldquo", "“")
        put("rdquo", "”")
        put("laquo", "«")
        put("raquo", "»")
        put("ndash", "–")
        put("mdash", "—")
        // Accented letters: a letter and its mark, composed.
        val marks = listOf("acute" to '́', "grave" to '̀', "circ" to '̂', "uml" to '̈', "tilde" to '̃', "cedil" to '̧', "ring" to '̊')
        for (letter in "aeiouycnAEIOUYCN") {
            for ((name, mark) in marks) {
                val composed = Normalizer.normalize("$letter$mark", Normalizer.Form.NFC)
                if (composed.length == 1) put("$letter$name", composed)
            }
        }
    }

    /** [text] with its character references decoded. */
    fun decode(text: String): String {
        var result = text
        repeat(PASSES) {
            if ('&' !in result) return result
            val next = decodeOnce(result)
            if (next == result) return result
            result = next
        }
        return result
    }

    private fun decodeOnce(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val end = text.indexOf(';', i + 1)
            val reference = if (end < 0 || end - i - 1 > LONGEST || end == i + 1) null else resolve(text.substring(i + 1, end))
            if (reference == null) {
                out.append(c)
                i++
            } else {
                out.append(reference)
                i = end + 1
            }
        }
        return out.toString()
    }

    private fun resolve(name: String): String? {
        if (name[0] != '#') return named[name]
        val hex = name.length > 1 && (name[1] == 'x' || name[1] == 'X')
        val digits = if (hex) name.substring(2) else name.substring(1)
        if (digits.isEmpty() || digits.length > 7) return null
        val value = digits.toIntOrNull(if (hex) 16 else 10) ?: return null
        // No control character, no surrogate, nothing past Unicode.
        if (value < 0x20 && value != 0x09 && value != 0x0A && value != 0x0D) return null
        if (value in 0xD800..0xDFFF || value > 0x10FFFF || value == 0x7F) return null
        return String(Character.toChars(value))
    }
}
