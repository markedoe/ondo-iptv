package com.markedoe.ondo.core.model

import java.util.UUID
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

/** A user made list of channels / movies / series ("list manager"). */
@Serializable
data class CustomList(
    /** Random UUID string; required when decoding. */
    @Required val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val items: List<FavoriteEntry> = emptyList(),
    /** Last edit (epoch millis), used to resolve conflicts between devices (last writer wins per list). */
    val updatedAt: Long = 0L,
) {
    fun contains(reference: MediaReference): Boolean = items.any { it.reference.itemKey == reference.itemKey }
}
