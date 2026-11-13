package com.markedoe.ondo

import com.markedoe.ondo.data.OndoState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SplashConditionTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @Test
    fun theSplashWaitsForThePlaylistsThenBrieflyForTheSavedCatalogue() {
        assertTrue("playlists not read yet", MainActivity.keepsSplash(OndoState(), elapsedMs = 10_000))
        val restoring = OndoState(isReady = true, isRestoring = true)
        assertTrue("catalogue being read", MainActivity.keepsSplash(restoring, elapsedMs = 500))
        assertFalse("not longer than the limit", MainActivity.keepsSplash(restoring, MainActivity.SPLASH_RESTORE_LIMIT_MS))
        assertFalse("catalogue read, or no playlist", MainActivity.keepsSplash(OndoState(isReady = true), elapsedMs = 0))
    }

    /** Home kept from the last start, drawn while the catalogue is read: the splash screen goes at once. */
    @Test
    fun homeKeptFromTheLastStartEndsTheSplashAtOnce() {
        val restoring = OndoState(isReady = true, isRestoring = true)
        assertFalse("Home with its carousel there", MainActivity.keepsSplash(restoring, elapsedMs = 50, homeShown = true))
        assertTrue("not before the playlists are read", MainActivity.keepsSplash(OndoState(), elapsedMs = 50, homeShown = true))
    }
}
