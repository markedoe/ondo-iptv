package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.HangGuard
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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
    fun onlyAServerOutOfReachOrBusyIsAskedAgain() {
        assertTrue("refused", ArtworkAttempts.isRetryable(ConnectException("Failed to connect")))
        assertTrue("reset", ArtworkAttempts.isRetryable(SocketException("Connection reset")))
        assertTrue("DNS", ArtworkAttempts.isRetryable(UnknownHostException("panel.example.com")))
        assertTrue("connect timeout", ArtworkAttempts.isRetryable(SocketTimeoutException("Connect timed out")))
        assertTrue("cut", ArtworkAttempts.isRetryable(IOException("unexpected end of stream", EOFException())))
        assertTrue("wrapped", ArtworkAttempts.isRetryable(IllegalStateException("load failed", ConnectException())))
        assertTrue(ArtworkAttempts.isRetryable(null, status = 503))
        assertTrue(ArtworkAttempts.isRetryable(null, status = 429))
        assertTrue(ArtworkAttempts.isRetryable(null, status = 408))
        // A picture that stopped arriving would start over from nothing: the next address instead.
        assertFalse("read timeout", ArtworkAttempts.isRetryable(SocketTimeoutException("timeout")))
        assertFalse("whole call timeout", ArtworkAttempts.isRetryable(InterruptedIOException("timeout")))
        assertFalse(ArtworkAttempts.isRetryable(null, status = 404))
        assertFalse("the status decides, not the message", ArtworkAttempts.isRetryable(RuntimeException("HTTP 503"), status = 403))
        assertFalse("a message is not a status", ArtworkAttempts.isRetryable(RuntimeException("HTTP 503")))
        assertFalse("not a picture", ArtworkAttempts.isRetryable(IllegalStateException("Failed to decode")))
        assertFalse(ArtworkAttempts.isRetryable(null))
    }

    @Test
    fun aHostThatKeepsFailingIsNotAskedAgainForAWhile() {
        var time = 1_000_000L
        val breaker = ArtworkBreaker(threshold = 5, windowMs = 30_000, pauseMs = 60_000, now = { time })
        val dead = ArtworkAttempts.start("http://dead.example.com/a.jpg", listOf(still))
        val refused = ConnectException("Failed to connect")
        // Four cards fail: each is asked again.
        repeat(4) { assertEquals(1, dead.afterFailure(refused, null, breaker).retries) }
        // The fifth failure within 30 s pauses the host: its card goes to the still at once.
        assertEquals(still, dead.afterFailure(refused, null, breaker).url)
        assertFalse(breaker.allowsRetry("dead.example.com"))
        assertTrue("other hosts are not paused", breaker.allowsRetry("panel.example.com"))
        time += 60_001
        assertTrue("asked again once the pause is over", breaker.allowsRetry("dead.example.com"))
    }

    @Test
    fun failuresFarApartOrFollowedByAPictureDoNotPauseTheHost() {
        var time = 0L
        val breaker = ArtworkBreaker(threshold = 5, windowMs = 30_000, pauseMs = 60_000, now = { time })
        repeat(10) {
            breaker.failed("slow.example.com")
            time += 10_000
        }
        assertTrue("never five within 30 s", breaker.allowsRetry("slow.example.com"))
        repeat(4) { breaker.failed("panel.example.com") }
        breaker.succeeded("http://panel.example.com/images/e1.jpg")
        breaker.failed("panel.example.com")
        assertTrue("a picture that loaded forgets the failures", breaker.allowsRetry("panel.example.com"))
        // A failure not worth another try (a 404) does not count.
        val missing = ArtworkAttempts.start("http://cdn.example.com/a.jpg")
        repeat(10) { missing.afterFailure(null, 404, breaker) }
        assertTrue(breaker.allowsRetry("cdn.example.com"))
    }
}
