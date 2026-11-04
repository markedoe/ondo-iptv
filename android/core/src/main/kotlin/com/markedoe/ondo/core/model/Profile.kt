package com.markedoe.ondo.core.model

import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

/**
 * A viewer profile: own history, lists and hidden categories (docs/PROFILES.md). Kids profiles only see what is allowed.
 * The same record as Apple's `Profile`.
 */
@Serializable
data class Profile(
    @Required val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    /** Index in the app's colour palette. */
    val colorIndex: Int = 0,
    /** What the avatar shows: [INITIAL], one of [SYMBOLS], or an emoji (anything else shows the initial). */
    val symbol: String = "person.fill",
    val isKids: Boolean = false,
    /** SHA-256 of the profile PIN ([hashPin]), `null` when the profile is not protected. Never synced. */
    val pinHash: String? = null,
    /** When it was added or last changed (sync's clock); 0: never changed (the main one as made), not synced. */
    val updatedAt: Long = 0,
) {
    val isProtected: Boolean
        get() = pinHash != null

    val isMain: Boolean
        get() = id == DEFAULT_ID

    /** Name of the library document of this profile. */
    val libraryDocumentName: String
        get() = if (id == DEFAULT_ID) "library" else "library-$id"

    /** The name's first letter, upper case, for the avatar ("?" for a profile without a name). */
    val initial: String
        get() = name.trim().let { if (it.isEmpty()) "?" else it.substring(0, it.offsetByCodePoints(0, 1)).uppercase() }

    companion object {
        /** The profile created automatically; its library keeps the historical document name. */
        const val DEFAULT_ID = "00000000-0000-0000-0000-000000000001"

        /** The avatar shows the name's first letter. */
        const val INITIAL = "initial"

        /** The icons both platforms draw (Apple's SF Symbols names; Android draws its own of each). */
        val SYMBOLS = listOf("person.fill", "face.smiling", "star.fill", "heart.fill", "gamecontroller.fill", "film.fill", "pawprint.fill", "leaf.fill")

        /** The emoji offered for an avatar (any single emoji received is drawn too). */
        val EMOJI = listOf("🦊", "🐼", "🦄", "🐙", "🚀", "🌈")

        /** At most this many profiles: as many as sync carries (`SyncProfile.MAX_PROFILES`). */
        const val MAX_PROFILES = 16

        /** How many colours the palette has. */
        const val COLOR_COUNT = 8

        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 8

        /** Wrong PINs in a row allowed before each try waits ([pinDelayMs]). */
        const val FREE_PIN_TRIES = 4

        fun isValidPin(pin: String): Boolean = pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it in '0'..'9' }

        /** The hash kept for [pin]: SHA-256 of `ondo:` and the PIN, in hexadecimal, as on Apple (`Keychain.hash(pin:)`). */
        fun hashPin(pin: String): String =
            MessageDigest.getInstance("SHA-256").digest("ondo:$pin".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        /**
         * Wait imposed after [failures] wrong PINs in a row: none for the first four, then 30 s, doubling up to an hour,
         * so that a 4-digit PIN cannot be found by trying them all (Apple's `AppModel.pinDelay`).
         */
        fun pinDelayMs(failures: Int): Long? {
            if (failures <= FREE_PIN_TRIES) return null
            val doublings = (failures - FREE_PIN_TRIES - 1).coerceAtMost(12)
            return (30_000L shl doublings).coerceAtMost(3_600_000L)
        }

        /** The main profile as made the first time (its library is the one that existed before profiles). */
        fun main(name: String) = Profile(id = DEFAULT_ID, name = name, colorIndex = 0, symbol = INITIAL)
    }
}
