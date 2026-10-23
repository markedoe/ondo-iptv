package com.markedoe.ondo.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * JSON configuration for everything the core persists (playlists, library, caches).
 *
 * Tolerant like the Swift decoders: unknown keys are ignored, missing fields take their default
 * values, `null` for a non-null field or an unknown enum value falls back to the default.
 */
val CoreJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * Deepest nesting of arrays and objects read from provider JSON. kotlinx.serialization reads nested arrays
 * recursively, so a few kilobytes of `[` overflow the stack; real playlists and panel answers nest a few levels.
 */
internal const val MAX_JSON_DEPTH = 512

/** Whether arrays and objects in [text] nest deeper than [max] (brackets inside strings do not count). */
internal fun jsonNestsDeeperThan(text: String, max: Int = MAX_JSON_DEPTH): Boolean {
    var depth = 0
    var inString = false
    var index = 0
    while (index < text.length) {
        val character = text[index]
        if (inString) {
            if (character == '\\') index++ else if (character == '"') inString = false
        } else {
            when (character) {
                '"' -> inString = true
                '[', '{' -> if (++depth > max) return true
                ']', '}' -> depth--
            }
        }
        index++
    }
    return false
}

/** Provider JSON parsed, or `null` when [text] is not JSON or nests deeper than [MAX_JSON_DEPTH]. */
internal fun parseJsonOrNull(text: String): JsonElement? {
    if (jsonNestsDeeperThan(text)) return null
    return try {
        Json.parseToJsonElement(text)
    } catch (_: IllegalArgumentException) { // SerializationException
        null
    }
}
