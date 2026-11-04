package com.markedoe.ondo.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The profile record both platforms share (docs/PROFILES.md). */
class ProfileTest {
    @Test
    fun thePinHashIsApplesOne() {
        // SHA-256 of "ondo:1234", as Apple's `Keychain.hash(pin:)` keeps it.
        assertEquals("3eccb8d7bd9f11fb865916baf7170bc4451e2cb18836635a9232411450d5695a", Profile.hashPin("1234"))
    }

    @Test
    fun wrongPinsWaitAsOnApple() {
        assertNull(Profile.pinDelayMs(Profile.FREE_PIN_TRIES))
        assertEquals(30_000L, Profile.pinDelayMs(5))
        assertEquals(60_000L, Profile.pinDelayMs(6))
        assertEquals(3_600_000L, Profile.pinDelayMs(40))
    }

    @Test
    fun aPinIsFourToEightDigits() {
        assertTrue(Profile.isValidPin("1234"))
        assertTrue(Profile.isValidPin("12345678"))
        assertFalse(Profile.isValidPin("123"))
        assertFalse(Profile.isValidPin("123456789"))
        assertFalse(Profile.isValidPin("12a4"))
    }

    @Test
    fun theMainProfileKeepsTheLibraryDocumentItAlwaysHad() {
        assertEquals("library", Profile.main("Me").libraryDocumentName)
        assertEquals("library-abc", Profile(id = "abc").libraryDocumentName)
        assertEquals("S", Profile(name = " sam").initial)
        assertEquals("?", Profile(name = "").initial)
    }

    @Test
    fun anAppleProfileIsReadAndUnknownFieldsAreIgnored() {
        val json = Json { ignoreUnknownKeys = true }
        val apple = """{"id":"6F9619FF-8B86-D011-B42D-00C04FC964FF","name":"Sam","colorIndex":2,"symbol":"star.fill","isKids":true,"pinHash":"ab","later":1}"""
        val profile = json.decodeFromString(Profile.serializer(), apple)
        assertEquals("Sam", profile.name)
        assertEquals(2, profile.colorIndex)
        assertTrue(profile.isKids)
        assertTrue(profile.isProtected)
        // An older record without the newer fields.
        val old = json.decodeFromString(Profile.serializer(), """{"id":"x"}""")
        assertEquals("person.fill", old.symbol)
        assertFalse(old.isKids)
    }
}
