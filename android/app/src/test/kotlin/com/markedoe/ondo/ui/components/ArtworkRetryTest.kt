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
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import com.markedoe.ondo.HangGuard
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * An episode's card whose picture fails: asked again after a network error, then TMDb's still, and the code on the
 * cover only while no picture shows. Before, one timeout left "S01E01" on the cover for as long as the page stayed
 * open, and a provider's address that led nowhere hid TMDb's still. The requests are counted at the image loader.
 */
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

    @OptIn(DelicateCoilApi::class)
    @Before
    fun setUp() {
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
    fun tearDown() = SingletonImageLoader.reset()

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

    private fun waitFor(what: String, done: () -> Boolean) {
        repeat(100) {
            if (done()) return
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
        }
        throw AssertionError("$what: ${requests.toList()}")
    }

    @Test
    fun aTimeoutIsAskedAgainThenTmdbsStillShows() {
        answers = mapOf(provider to IOException("timeout"), still to null)
        compose.setContent { Artwork(provider, "S01E01", Modifier.size(160.dp, 90.dp), fallbackUrls = listOf(still)) }
        compose.waitForIdle()
        assertEquals(provider, requests.first())
        assertTrue("the code shows while no picture does", codeShown())

        waitFor("TMDb's still asked") { still in requests }
        assertEquals("the provider's picture asked again twice first", listOf(provider, provider, provider, still), requests.toList())
        compose.waitForIdle()
        assertTrue("the still shows: the code goes", !codeShown())
    }

    @Test
    fun anAddressThatLeadsNowhereGivesWayToTheStillAtOnce() {
        answers = mapOf(provider to IllegalStateException("HTTP 404"), still to null)
        compose.setContent { Artwork(provider, "S01E01", Modifier.size(160.dp, 90.dp), fallbackUrls = listOf(still)) }
        compose.waitForIdle()
        assertEquals("no wait, no second try of an address that leads nowhere", listOf(provider, still), requests.toList())
        assertTrue(!codeShown())
    }

    @Test
    fun aCardShownAgainAsksAgain() {
        answers = mapOf(provider to IllegalStateException("HTTP 404"))
        var shown by mutableStateOf(true)
        compose.setContent { if (shown) Artwork(provider, "S01E01", Modifier.size(160.dp, 90.dp)) }
        compose.waitForIdle()
        assertEquals(listOf(provider), requests.toList())
        assertTrue("given up: the code on the cover", codeShown())

        shown = false
        compose.waitForIdle()
        answers = mapOf(provider to null)
        shown = true
        compose.waitForIdle()
        assertEquals("asked again when shown again", listOf(provider, provider), requests.toList())
        assertTrue(!codeShown())
    }
}
