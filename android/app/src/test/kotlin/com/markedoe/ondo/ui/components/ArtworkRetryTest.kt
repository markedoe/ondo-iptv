package com.markedoe.ondo.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.network.NetworkResponse
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.library.ArtworkBreaker
import java.net.ConnectException
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * An episode's card whose picture fails: asked again when its server could not be reached or was busy, then TMDb's still, and the code on the
 * cover only while no picture shows. Before, one timeout left "S01E01" on the cover for as long as the page stayed
 * open, and a provider's address that led nowhere hid TMDb's still. The requests are counted at the image loader.
 */
@OptIn(ExperimentalCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkRetryTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createComposeRule()

    private val provider = "http://panel.example.com/images/e1.jpg"
    private val still = "https://image.tmdb.org/t/p/w300/e1.jpg"

    /** The addresses asked, in order. */
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** How each address answers: a picture, or the error given. */
    private var answers: Map<String, Throwable?> = emptyMap()

    @OptIn(ExperimentalCoilApi::class, DelicateCoilApi::class)
    @Before
    fun setUp() {
        // A breaker of the test's own: the app's keeps what other tests failed.
        artworkBreaker = ArtworkBreaker()
        // The clock moves only when the test says: the waits between tries are stepped through, not run at once.
        compose.mainClock.autoAdvance = false
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val loader = ImageLoader.Builder(context)
            .components { add(Answers()) }
            .coroutineContext(Dispatchers.Unconfined)
            .mainCoroutineContext(Dispatchers.Unconfined)
            .build()
        SingletonImageLoader.setUnsafe(loader)
    }

    @OptIn(DelicateCoilApi::class)
    @After
    fun tearDown() {
        SingletonImageLoader.reset()
        artworkBreaker = ArtworkBreaker.shared
    }

    private inner class Answers : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val request = chain.request
            val url = request.data as String
            requests += url
            val error = answers[url] ?: if (url in answers) null else IllegalArgumentException("Unknown: $url")
            if (error != null) return ErrorResult(null, request, error)
            return SuccessResult(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImage(), request, DataSource.NETWORK)
        }
    }

    private fun codeShown() = compose.onAllNodesWithText("S01E01").fetchSemanticsNodes().isNotEmpty()

    /** Moves the clock [ms] on, a frame at a time. */
    private fun step(ms: Long) {
        repeat((ms / 20).toInt()) {
            compose.mainClock.advanceTimeBy(20)
            compose.waitForIdle()
        }
    }

    private fun status(code: Int) = HttpException(NetworkResponse(code = code))

    @Test
    fun aRefusedConnectionIsAskedAgainThenTmdbsStillShows() {
        answers = mapOf(provider to ConnectException("Failed to connect"), still to null)
        compose.setContent { Artwork(provider, "S01E01", Modifier.size(160.dp, 90.dp), fallbackUrls = listOf(still)) }
        step(200)
        assertEquals(listOf(provider), requests.toList())
        assertTrue("the code shows while no picture does", codeShown())

        step(1_000)
        assertEquals("not before its wait", listOf(provider), requests.toList())
        step(600)
        assertEquals("asked again after 1.5 s", listOf(provider, provider), requests.toList())
        step(5_200)
        assertEquals("then after 5 s, then the still", listOf(provider, provider, provider, still), requests.toList())
        step(200)
        assertTrue("the still shows: the code goes", !codeShown())
    }

    @Test
    fun aBusyServerIsAskedAgainAMissingPictureGivesWayAtOnce() {
        answers = mapOf(provider to status(503), still to null)
        compose.setContent { Artwork(provider, "S01E01", Modifier.size(160.dp, 90.dp), fallbackUrls = listOf(still)) }
        step(1_800)
        assertEquals("503: asked again", listOf(provider, provider), requests.toList())
    }

    @Test
    fun anAddressThatLeadsNowhereGivesWayToTheStillAtOnce() {
        answers = mapOf(provider to status(404), still to null)
        compose.setContent { Artwork(provider, "S01E01", Modifier.size(160.dp, 90.dp), fallbackUrls = listOf(still)) }
        step(200)
        assertEquals("no wait, no second try of an address that leads nowhere", listOf(provider, still), requests.toList())
        step(10_000)
        assertEquals(listOf(provider, still), requests.toList())
        assertTrue(!codeShown())
    }

    @Test
    fun theStatusIsReadFromCoilsAnswer() {
        assertEquals(404, artworkStatus(status(404)))
        assertEquals(503, artworkStatus(status(503)))
        assertNull(artworkStatus(ConnectException()))
        assertTrue(com.markedoe.ondo.core.library.ArtworkAttempts.isRetryable(status(503), artworkStatus(status(503))))
        assertTrue(com.markedoe.ondo.core.library.ArtworkAttempts.isRetryable(status(429), artworkStatus(status(429))))
        assertTrue(!com.markedoe.ondo.core.library.ArtworkAttempts.isRetryable(status(404), artworkStatus(status(404))))
    }

    @Test
    fun aCardShownAgainAsksAgain() {
        answers = mapOf(provider to status(404))
        var shown by mutableStateOf(true)
        compose.setContent { if (shown) Artwork(provider, "S01E01", Modifier.size(160.dp, 90.dp)) }
        step(200)
        assertEquals(listOf(provider), requests.toList())
        assertTrue("given up: the code on the cover", codeShown())

        shown = false
        step(100)
        answers = mapOf(provider to null)
        shown = true
        step(200)
        assertEquals("asked again when shown again", listOf(provider, provider), requests.toList())
        assertTrue(!codeShown())
    }
}
