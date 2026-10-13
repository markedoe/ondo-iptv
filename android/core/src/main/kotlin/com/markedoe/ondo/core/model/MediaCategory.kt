package com.markedoe.ondo.core.model

import kotlinx.serialization.Serializable

/** A group of channels / movies / series (`group-title` in M3U, category in Xtream). */
@Serializable
data class MediaCategory(
    val id: String,
    val name: String,
    val type: ContentType,
) {
    /** Heuristic used by parental control to lock adult groups by default. */
    val looksAdult: Boolean
        get() = AdultContentDetector.isAdult(name)
}

/** Keyword heuristic for adult category names. */
object AdultContentDetector {
    private val keywords = listOf("xxx", "adult", "adulte", "18+", "+18", "porn", "erotic", "érotique", "for adults")

    fun isAdult(text: String): Boolean {
        val lower = text.lowercase()
        return keywords.any { lower.contains(it) }
    }
}
