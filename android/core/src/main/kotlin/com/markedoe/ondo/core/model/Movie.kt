package com.markedoe.ondo.core.model

import kotlinx.serialization.Serializable

/** A video on demand. */
@Serializable
data class Movie(
    val id: String,
    val name: String,
    val posterUrl: String? = null,
    val categoryId: String,
    val streamUrl: String,
    val rating: Double? = null,
    val year: Int? = null,
    /** When the provider added it, in epoch **seconds**. */
    val addedAt: Long? = null,
    val plot: String? = null,
    val xtreamStreamId: Int? = null,
    val containerExtension: String? = null,
    val headers: HttpHeaders? = null,
)
