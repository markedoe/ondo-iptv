package com.markedoe.ondo.core.model

import kotlinx.serialization.Serializable

/** A favourite (or an item of a custom list). */
@Serializable
data class FavoriteEntry(
    val reference: MediaReference,
    val title: String,
    val imageUrl: String? = null,
    /** Epoch millis. */
    val addedAt: Long = 0L,
    /** Manual ordering inside the favourites list. */
    val sortIndex: Int = 0,
) {
    val id: String
        get() = reference.itemKey
}
