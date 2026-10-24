package com.markedoe.ondo.core.xtream

import com.markedoe.ondo.core.MAX_JSON_DEPTH
import com.markedoe.ondo.core.jsonNestsDeeperThan
import com.markedoe.ondo.core.net.Gzip
import com.markedoe.ondo.core.net.GzipException
import com.markedoe.ondo.core.net.HttpException
import com.markedoe.ondo.core.numberString
import com.markedoe.ondo.core.toStrictDoubleOrNull
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeToSequence

// Xtream panels are notoriously inconsistent: the same field can be an Int, a String, a Bool, `null`, an
// empty array… These helpers never throw for a wrong type, they return `null` (Swift LenientDecoding.swift).

private val JsonElement?.primitive: JsonPrimitive?
    get() = if (this is JsonPrimitive && this !is JsonNull) this else null

private fun JsonPrimitive.isBooleanLiteral() = !isString && (content == "true" || content == "false")

/** Integer JSON literal (not a string). */
private fun JsonPrimitive.integerLiteral(): Long? = if (isString) null else content.toLongOrNull()

/** Any JSON number literal (not a string, not a boolean). */
private fun JsonPrimitive.numberLiteral(): Double? =
    if (isString || isBooleanLiteral()) null else content.toDoubleOrNull()?.takeIf { it.isFinite() }

/** String, or Int / Double / Bool rendered as text, or the first element of an array of strings. */
internal fun JsonElement?.lenientString(): String? {
    if (this is JsonArray) {
        if (any { !(it is JsonPrimitive && it !is JsonNull && it.isString) }) return null
        return (firstOrNull() as? JsonPrimitive)?.content
    }
    val p = primitive ?: return null
    if (p.isString) return p.content
    p.integerLiteral()?.let { return it.toString() }
    p.numberLiteral()?.let { return numberString(it) }
    if (p.isBooleanLiteral()) return if (p.content == "true") "1" else "0"
    return null
}

/**
 * 64-bit integer from a number, a numeric string (`"7"`, `"7.9"` -> 7) or a boolean. A number beyond the
 * 64-bit range (`1e30`) is dropped rather than clamped.
 */
internal fun JsonElement?.lenientLong(): Long? {
    val p = primitive ?: return null
    if (!p.isString) {
        p.integerLiteral()?.let { return it }
        if (p.isBooleanLiteral()) return if (p.content == "true") 1 else 0
        return p.numberLiteral()?.let(::truncatedLong)
    }
    val trimmed = p.content.trim()
    trimmed.toLongOrNull()?.let { return it }
    return trimmed.toStrictDoubleOrNull()?.let(::truncatedLong)
}

/** `value` without its fraction, or `null` when it is not finite or does not fit a `Long` (Swift `Int(exactly:)`). */
private fun truncatedLong(value: Double): Long? =
    if (value.isFinite() && value >= -TWO_POW_63 && value < TWO_POW_63) value.toLong() else null

private const val TWO_POW_63 = 9.223372036854775808e18

/** [lenientLong] when it fits an `Int`. */
internal fun JsonElement?.lenientInt(): Int? =
    lenientLong()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

/**
 * A year (2021, "2021"), else the one [date] starts with ("2021-05-04"): anything but four digits (0 for none, -1,
 * 1e18) is no year.
 */
internal fun JsonElement?.lenientYear(date: String? = null): Int? =
    listOfNotNull(lenientInt(), date?.take(4)?.toIntOrNull()).firstOrNull { it in 1_000..9_999 }

/** Number, or numeric string (`","` accepted as decimal separator). Booleans are not numbers. */
internal fun JsonElement?.lenientDouble(): Double? {
    val p = primitive ?: return null
    if (!p.isString) return p.numberLiteral()
    return p.content.trim().replace(",", ".").toStrictDoubleOrNull()?.takeIf { it.isFinite() }
}

/** Boolean, non-zero number, or `"true"` / `"yes"` / `"active"`. */
internal fun JsonElement?.lenientBool(): Boolean? {
    val p = primitive ?: return null
    if (p.isBooleanLiteral()) return p.content == "true"
    lenientLong()?.let { return it != 0L }
    if (p.isString) return p.content.lowercase() in setOf("true", "yes", "active")
    return null
}

/** Array of strings, or a single non-empty string (`backdrop_path`). */
internal fun JsonElement?.lenientStringArray(): List<String> {
    if (this is JsonArray) {
        if (all { it is JsonPrimitive && it !is JsonNull && it.isString }) return map { (it as JsonPrimitive).content }
        return emptyList()
    }
    val p = primitive ?: return emptyList()
    return if (p.isString && p.content.isNotEmpty()) listOf(p.content) else emptyList()
}

/** Unix timestamp (seconds) as number or string; `null` when missing or not positive. */
internal fun JsonElement?.lenientEpochSeconds(): Long? = lenientLong()?.takeIf { it > 0 }

/**
 * Elements of an array, dropping the ones `decode` rejects. Panels return `{}` / `null` / `false` for empty
 * lists, and sometimes an object keyed by index instead of an array.
 */
internal inline fun <T> JsonElement?.lossyList(decode: (JsonElement) -> T?): List<T> = when (this) {
    is JsonArray -> mapNotNull(decode)
    is JsonObject -> entries.sortedBy { it.key.toIntOrNull() ?: 0 }.mapNotNull { decode(it.value) }
    else -> emptyList()
}

/**
 * The elements of the JSON list saved in [file] (gzip or not, with or without a byte order mark), decoded one at a
 * time: only the element being read is in memory as JSON. An answer that is not an array (`{}` or `null` for an
 * empty list, an object keyed by index) is small and read whole, as [lossyList] reads it.
 * [checkActive] runs before each read of the check of the nesting, and throws to stop it.
 * @throws HttpException.EmptyResponse for an empty answer
 * @throws XtreamException.InvalidResponse for an answer of spaces only, or one that nests deeper than [MAX_JSON_DEPTH]
 * @throws XtreamException.BrokenList for an answer that is not JSON: a list cut short or broken half-way (an error
 *   printed in the middle, a gzip download cut off), or a page in place of the list. Unlike `{}` or a 404, it does
 *   not mean that the panel offers nothing there.
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun <T> decodeList(file: File, checkActive: () -> Unit = {}, decode: (JsonElement) -> T?): List<T> = try {
    // kotlinx reads nested arrays recursively: a few kilobytes of `[` would overflow the stack.
    val checked = object : FilterInputStream(Gzip.decompressingStream(file.inputStream())) {
        override fun read(): Int {
            checkActive()
            return super.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            checkActive()
            return super.read(b, off, len)
        }
    }
    if (jsonNestsDeeperThan(checked)) throw XtreamException.InvalidResponse()
    BufferedInputStream(Gzip.decompressingStream(file.inputStream()), 64 * 1024).use { input ->
        input.mark(4)
        if (!(input.read() == 0xEF && input.read() == 0xBB && input.read() == 0xBF)) input.reset()
        // The first character tells an array from anything else.
        var first: Int
        var sawAnything = false
        do {
            input.mark(1)
            first = input.read()
            if (first >= 0) sawAnything = true
        } while (first == ' '.code || first == '\n'.code || first == '\r'.code || first == '\t'.code)
        if (first < 0) throw if (sawAnything) XtreamException.InvalidResponse() else HttpException.EmptyResponse()
        input.reset()
        if (first == '['.code) {
            Json.decodeToSequence(input, JsonElement.serializer(), DecodeSequenceMode.ARRAY_WRAPPED).mapNotNull(decode).toList()
        } else {
            val element = Json.parseToJsonElement(input.readBytes().decodeToString())
            // kotlinx takes a lone word without quotes (a page without spaces, `<html>…</html>`) for a value.
            if (element is JsonPrimitive && element !is JsonNull && !element.isString && !element.isBooleanLiteral() &&
                element.content.toDoubleOrNull() == null
            ) {
                throw XtreamException.BrokenList()
            }
            element.lossyList(decode)
        }
    }
} catch (e: IllegalArgumentException) { // SerializationException: not JSON, or cut off
    throw XtreamException.BrokenList()
} catch (e: java.util.zip.ZipException) {
    throw GzipException("Could not inflate the data: ${e.message}", e)
} catch (e: java.io.EOFException) {
    // A gzip answer cut off: what was inflated is JSON cut off too.
    throw XtreamException.BrokenList()
}
