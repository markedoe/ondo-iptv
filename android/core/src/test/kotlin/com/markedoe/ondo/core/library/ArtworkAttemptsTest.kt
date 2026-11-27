package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.HangGuard
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A card's picture is not given up at its first failed load: a network failure is asked again a moment later, then the
 * next address (TMDb's still of an episode) is tried, and only then the generated cover shows its title. Before, the
 * cover kept its title for as long as the card stayed on screen after a single timeout.
 */
class ArtworkAttemptsTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val provider = "http://panel.example.com/images/e1.jpg"
    private val still = "https://image.tmdb.org/t/p/w300/e1.jpg"

    @Test
    fun aTimeoutIsAskedAgainThenTheStillThenTheCover() {
        val start = ArtworkAttempts.start(provider, listOf(still))
        assertEquals(provider, start.url)
        val seen = mutableListOf(start.attempt)

        val again = start.failed(transient = true)
        assertEquals("the same address, asked again", provider, again.url)
        assertTrue("after a wait", again.waitMs > 0)
        seen += again.attempt

        val third = again.failed(transient = true)
        assertEquals(provider, third.url)
        assertTrue("waiting longer", third.waitMs > again.waitMs)
        seen += third.attempt

        val fallback = third.failed(transient = true)
        assertEquals("then TMDb's still, at once", still, fallback.url)
        assertEquals(0L, fallback.waitMs)
        seen += fallback.attempt

        val stillBroken = fallback.failed(transient = false)
        assertTrue("nothing left: the cover with its title", stillBroken.gaveUp)
        assertNull(stillBroken.url)
        assertEquals("every request is a new one", seen.size, seen.toSet().size)
    }

    @Test
    fun anAddressThatLeadsNowhereGoesStraightToTheNext() {
        val next = ArtworkAttempts.start(provider, listOf(still)).failed(transient = false)
        assertEquals(still, next.url)
        assertEquals(0, next.retries)
        assertTrue(ArtworkAttempts.start(provider).failed(transient = false).gaveUp)
    }

    @Test
    fun aCardShownAgainStartsOver() {
        // What a card keeps of its tries lives with it: shown again, it starts from the provider's picture.
        val gaveUp = generateSequence(ArtworkAttempts.start(provider)) { it.failed(transient = true) }.first { it.gaveUp }
        assertTrue(gaveUp.gaveUp)
        val shownAgain = ArtworkAttempts.start(provider)
        assertEquals(provider, shownAgain.url)
        assertFalse(shownAgain.gaveUp)
    }

    @Test
    fun blankAndRepeatedAddressesAreLeftOut() {
        assertEquals(listOf(provider), ArtworkAttempts.start(" $provider ", listOf(null, "", provider)).urls)
        assertTrue(ArtworkAttempts.start(null, listOf(" ")).urls.isEmpty())
        assertEquals(listOf(still), ArtworkAttempts.start("", listOf(still)).urls)
    }

    @Test
    fun theNetworkAndABusyServerAreWorthAnotherTryNotAMissingPicture() {
        assertTrue(ArtworkAttempts.isTransient(SocketTimeoutException("timeout")))
        assertTrue(ArtworkAttempts.isTransient(IOException("unexpected end of stream")))
        assertTrue(ArtworkAttempts.isTransient(RuntimeException("HTTP 503")))
        assertTrue(ArtworkAttempts.isTransient(RuntimeException("HTTP 429")))
        assertTrue(ArtworkAttempts.isTransient(IllegalStateException("load failed", SocketTimeoutException())))
        assertFalse(ArtworkAttempts.isTransient(RuntimeException("HTTP 404")))
        assertFalse(ArtworkAttempts.isTransient(IOException("HTTP 403")))
        assertFalse("not a picture", ArtworkAttempts.isTransient(IllegalStateException("Failed to decode")))
        assertFalse(ArtworkAttempts.isTransient(null))
    }
}
