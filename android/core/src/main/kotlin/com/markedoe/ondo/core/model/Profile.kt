package com.markedoe.ondo.core.model

import java.util.UUID
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

/** A viewer profile: own favourites, history, lists. Kids profiles only see unlocked content. */
@Serializable
data class Profile(
    @Required val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    /** Index in the app's colour palette. */
    val colorIndex: Int = 0,
    val symbol: String = "person.fill",
    val isKids: Boolean = false,
    /** SHA-256 of the profile PIN, `null` when the profile is not protected. */
    val pinHash: String? = null,
) {
    val isProtected: Boolean
        get() = pinHash != null

    /** Name of the library document of this profile. */
    val libraryDocumentName: String
        get() = if (id == DEFAULT_ID) "library" else "library-$id"

    companion object {
        /** The profile created automatically; its library keeps the historical document name. */
        const val DEFAULT_ID = "00000000-0000-0000-0000-000000000001"
    }
}
