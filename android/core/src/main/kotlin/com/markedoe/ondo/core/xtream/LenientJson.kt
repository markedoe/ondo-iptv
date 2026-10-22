package com.markedoe.ondo.core.xtream

import com.markedoe.ondo.core.numberString
import com.markedoe.ondo.core.toStrictDoubleOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

/** Number, or numeric string (`","` accepted as decimal separator). Booleans are not numbers. */
internal fun JsonElement?.lenientDouble(): Double? {
    val p = primitive ?: return null
    if (!p.isString) return p.numberLiteral()
    return p.content.trim().replace(",", ".").toStrictDoubleOrNull()
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
