package com.markedoe.ondo.core

/**
 * Pieces of regular expressions that read the same on Android, whose `java.util.regex` is ICU's engine, and on the JDK
 * the unit tests run on (and as Apple's NSRegularExpression, ICU too). Neither engine's Unicode flag is used: ICU refuses
 * the JDK's at compile time (a PatternSyntaxException on a device only), and without it the JDK's `\s`, `\S`, `\d` and
 * `\b` are ASCII while ICU's are Unicode. The classes are spelled out instead (PortableRegexTest).
 */
object PortableRegex {
    /** Unicode whitespace, inside a class: a no-break space, U+2003 and U+0085 too, as Apple's `whitespacesAndNewlines`. */
    const val SPACE = """\t\n\x0B\f\r\u0085\p{Z}"""

    /** A word's character, as `\b` sees it with Unicode classes: letters, marks, decimal digits, `_`, the joiners. */
    const val WORD = """[\p{L}\p{M}\p{Nd}\p{Pc}\u200C\u200D]"""

    /** A word boundary with Unicode classes, on both engines. */
    const val BOUNDARY = """(?:(?<=$WORD)(?!$WORD)|(?<!$WORD)(?=$WORD))"""

    /** A decimal digit of any script, as `\d` with Unicode classes. */
    const val DIGIT = """\p{Nd}"""
}
