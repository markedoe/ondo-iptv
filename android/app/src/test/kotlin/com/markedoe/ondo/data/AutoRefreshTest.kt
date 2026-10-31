package com.markedoe.ondo.data

import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.net.HttpException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Back in Ondo, the playlist in use is downloaded again once older than the viewer's choice; never with "only when I ask". */
class AutoRefreshTest {
    @get:Rule val folder = TemporaryFolder()

    private val list = "http://provider.example.com/list.m3u"
    private val catalogue = """
        #EXTM3U
        #EXTINF:5400 group-title="Movies | Drama",Quiet Water (2021)
        http://provider.example.com/quiet.mp4
    """.trimIndent()

    private class Http(val body: String) : HttpClient {
        val requested = mutableListOf<String>()
        override suspend fun get(url: String, headers: Map<String, String>): ByteArray {
            requested += url
            if (url.endsWith("list.m3u")) return body.encodeToByteArray()
            throw HttpException.BadStatus(404)
        }
    }

    private var time = 1_760_000_000_000L

    private fun TestScope.device(http: Http) =
        OndoRepository(AppStore(folder.newFolder("tv")), http, backgroundScope, now = { time }, io = StandardTestDispatcher(testScheduler))

    @Test
    fun thePlaylistIsDownloadedAgainOnceOlderThanTheChoice() = runTest {
        val http = Http(catalogue)
        val tv = device(http)
        tv.bootstrap()
        tv.state.first { it.isReady }
        tv.addPlaylist("Home", list)
        tv.state.first { it.content.movies.isNotEmpty() && !it.isLoading }
        fun downloads() = http.requested.count { it == list }
        assertEquals(1, downloads())

        time += 2 * OndoRepository.HOUR_MS
        tv.refreshIfStale()
        assertEquals("a day not passed: kept", 1, downloads())

        time += 23 * OndoRepository.HOUR_MS
        tv.refreshIfStale()
        tv.state.first { !it.isLoading && it.content.fetchedAt == time }
        assertEquals("a day later: downloaded again", 2, downloads())

        tv.setAutoRefreshHours(0)
        time += 200 * OndoRepository.HOUR_MS
        tv.refreshIfStale()
        assertEquals("only when asked", 2, downloads())
    }
}
