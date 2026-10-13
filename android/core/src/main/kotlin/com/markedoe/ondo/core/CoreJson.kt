package com.markedoe.ondo.core

import kotlinx.serialization.json.Json

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
