package com.markedoe.ondo

import com.markedoe.ondo.data.OndoState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashConditionTest {
    @Test
    fun theSplashWaitsForThePlaylistsThenBrieflyForTheSavedCatalogue() {
        assertTrue("playlists not read yet", MainActivity.keepsSplash(OndoState(), elapsedMs = 10_000))
        val restoring = OndoState(isReady = true, isRestoring = true)
        assertTrue("catalogue being read", MainActivity.keepsSplash(restoring, elapsedMs = 500))
        assertFalse("not longer than the limit", MainActivity.keepsSplash(restoring, MainActivity.SPLASH_RESTORE_LIMIT_MS))
        assertFalse("catalogue read, or no playlist", MainActivity.keepsSplash(OndoState(isReady = true), elapsedMs = 0))
    }
}
