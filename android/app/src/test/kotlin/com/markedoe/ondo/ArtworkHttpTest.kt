package com.markedoe.ondo

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Pictures never wait behind the provider's API: the provider's server answers both (its catalogue, details and guide,
 * and its episodes' pictures), and the shared client takes 5 requests per host at once. A picture also gives up within
 * [ARTWORK_CALL_TIMEOUT_S], not the shared client's 10 minutes, so a stalled one does not hold a place for long, yet
 * leaves a slow server's large picture the time to arrive.
 */
class ArtworkHttpTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    /** Releases the provider's slow API answers. */
    private val release = CountDownLatch(1)

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

    /** The app's shared client, against a provider whose API answers only when [release]d and whose pictures at once. */
    private val shared = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        // Copied to the artwork client with the rest: it tells the API from the pictures by the path.
        if (request.url.encodedPath.endsWith("player_api.php")) release.await(20, TimeUnit.SECONDS)
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(png.toResponseBody("image/png".toMediaType()))
            .build()
    }.build()

    private val artwork = artworkHttp(shared)

    @After
    fun tearDown() {
        release.countDown()
        shared.dispatcher.cancelAll()
        artwork.dispatcher.cancelAll()
    }

    private fun enqueue(client: OkHttpClient, url: String, done: CountDownLatch? = null) {
        client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}

            override fun onResponse(call: Call, response: Response) {
                response.close()
                done?.countDown()
            }
        })
    }

    @Test
    fun anEpisodesPictureIsNotQueuedBehindTheProvidersSlowApi() {
        // Five slow details requests (an idle version fetch, a series' seasons…) fill the shared client's places on
        // the provider's server, and the next one waits.
        repeat(6) { enqueue(shared, "http://panel.example.com/player_api.php?action=get_vod_info&vod_id=$it") }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (shared.dispatcher.runningCallsCount() < 5 && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(5, shared.dispatcher.runningCallsCount())
        assertEquals("the shared queue is full: a picture there would wait", 1, shared.dispatcher.queuedCallsCount())

        // A picture on the same server, through artwork's client: it arrives while the API still waits.
        val pictures = CountDownLatch(3)
        repeat(3) { enqueue(artwork, "http://panel.example.com/images/episode-$it.jpg", pictures) }
        assertTrue("the pictures arrive while the API waits", pictures.await(5, TimeUnit.SECONDS))
        assertEquals("the API still waits", 5, shared.dispatcher.runningCallsCount())
    }

    @Test
    fun artworkHasItsOwnQueueAndGivesUpSooner() {
        assertNotSame(shared.dispatcher, artwork.dispatcher)
        assertEquals(ARTWORK_REQUESTS_PER_HOST, artwork.dispatcher.maxRequestsPerHost)
        assertTrue("more places per host than the shared client", artwork.dispatcher.maxRequestsPerHost > shared.dispatcher.maxRequestsPerHost)
        val sharedWithTimeouts = OkHttpClient.Builder().callTimeout(600, TimeUnit.SECONDS).build()
        val timeout = artworkHttp(sharedWithTimeouts).callTimeoutMillis
        assertTrue("a whole picture within 2 minutes, not the shared 10: $timeout ms", timeout in 60_000..120_000)
        assertEquals(15_000, artwork.readTimeoutMillis)
        assertEquals(15_000, artwork.connectTimeoutMillis)
        assertEquals("the shared client's connections", shared.connectionPool, artwork.connectionPool)
    }
}
