package com.markedoe.ondo.core

import kotlin.math.abs
import kotlin.math.round

/** Trimmed string, or `null` when it is empty or only whitespace (Swift `nilIfBlank`). */
internal fun String.nilIfBlank(): String? {
    if (isEmpty()) return null
    // Fast path (hot in parsers): nothing to trim.
    if (!this[0].isWhitespace() && !this[length - 1].isWhitespace()) return this
    val trimmed = trim()
    return trimmed.ifEmpty { null }
}

/**
 * Strict decimal parsing (`-1`, `7200`, `8.8`, `.5`, `1e3`), like Swift's `Double(String)` for the
 * values providers send. Unlike Kotlin's `toDoubleOrNull()` it rejects `1d`, `0x1p3`, `NaN`, `Infinity`.
 */
internal fun String.toStrictDoubleOrNull(): Double? {
    val n = length
    if (n == 0) return null
    var i = 0
    if (this[i] == '+' || this[i] == '-') i++
    var digits = 0
    while (i < n && this[i] in '0'..'9') { i++; digits++ }
    if (i < n && this[i] == '.') {
        i++
        while (i < n && this[i] in '0'..'9') { i++; digits++ }
    }
    if (digits == 0) return null
    if (i < n && (this[i] == 'e' || this[i] == 'E')) {
        i++
        if (i < n && (this[i] == '+' || this[i] == '-')) i++
        var exponentDigits = 0
        while (i < n && this[i] in '0'..'9') { i++; exponentDigits++ }
        if (exponentDigits == 0) return null
    }
    if (i != n) return null
    return toDouble()
}

/** Swift `Character.isNewline`. */
internal fun Char.isNewline(): Boolean =
    this == '\n' || this == '\r' || this == '\u000B' || this == '\u000C' ||
        this == '\u0085' || this == ' ' || this == ' '

/** Non-empty lines, split on any newline character (Swift `split(whereSeparator: \.isNewline)`). */
internal fun String.nonEmptyLines(): List<String> {
    val result = ArrayList<String>()
    var start = 0
    for (i in indices) {
        if (this[i].isNewline()) {
            if (i > start) result.add(substring(start, i))
            start = i + 1
        }
    }
    if (start < length) result.add(substring(start))
    return result
}

/** UTF-8 text without its byte order mark, if any: JSON parsers reject one (Foundation's skips it). */
internal fun ByteArray.decodeToStringWithoutBom(): String {
    val hasBom = size >= 3 && this[0] == 0xEF.toByte() && this[1] == 0xBB.toByte() && this[2] == 0xBF.toByte()
    return if (hasBom) decodeToString(3, size) else decodeToString()
}

/** Whole doubles print as integers (`4.0` -> `"4"`), like `NSNumber.stringValue`. */
internal fun numberString(value: Double): String =
    if (round(value) == value && abs(value) < 9.2e18) value.toLong().toString() else value.toString()
