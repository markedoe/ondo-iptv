package com.markedoe.ondo.core.xtream

import com.markedoe.ondo.core.model.XtreamCredentials
import com.markedoe.ondo.core.model.XtreamOutputFormat
import com.markedoe.ondo.core.net.DEFAULT_IPTV_USER_AGENT
import com.markedoe.ondo.core.net.OkHttpClientAdapter
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * An Xtream panel end to end: the real OkHttp client against a local server that decodes the query like
 * a PHP panel ("+" is a space) and finds the account in stream paths. A password with + / & = must
 * reach it unchanged, or the login fails. Same test as the iPhone app's `PlaylistOverHTTPTests`.
 */
class XtreamOverHttpTest {
    private val server = MockWebServer()
    private val username = "viewer"
    private val password = "pa+ss/w&rd=1"

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun query(target: String): Map<String, String> =
        target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { pair ->
            URLDecoder.decode(pair.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(pair.substringAfter('=', ""), Charsets.UTF_8)
        }

    /** Path segments decoded one by one, as a web server splits them ("+" stays a plus in a path). */
    private fun segments(target: String): List<String> =
        target.substringBefore('?').split('/').filter { it.isNotEmpty() }
            .map { URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8) }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private val panel = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val target = request.path ?: return MockResponse().setResponseCode(404)
            if (target.startsWith("/movie/")) return MockResponse().setBody("stream")
            if (!target.startsWith("/player_api.php")) return MockResponse().setResponseCode(404)
            val query = query(target)
            if (query["username"] != username || query["password"] != password) return json("""{"user_info":{"auth":0}}""")
            return when (query["action"]) {
                null -> json(
                    """{"user_info":{"username":"viewer","auth":1,"status":"Active","exp_date":"1924992000","max_connections":"1",""" +
                        """"allowed_output_formats":["m3u8","ts"]},"server_info":{"url":"127.0.0.1","server_protocol":"http"}}""",
                )
                "get_live_categories" -> json("""[{"category_id":"1","category_name":"News","parent_id":0}]""")
                "get_live_streams" -> json("""[{"num":1,"name":"Harbor News","stream_type":"live","stream_id":101,"category_id":"1"}]""")
                "get_vod_categories" -> json("""[{"category_id":"10","category_name":"Drama","parent_id":0}]""")
                "get_vod_streams" -> json(
                    """[{"num":1,"name":"Quiet Water (2025)","stream_type":"movie","stream_id":5001,"category_id":"10",""" +
                        """"added":"1700000000","container_extension":"mp4"}]""",
                )
                "get_series_categories" -> json("""[{"category_id":"20","category_name":"Drama","parent_id":0}]""")
                "get_series" -> json("""[{"num":1,"name":"Harbor Lights","series_id":9001,"category_id":"20"}]""")
                else -> json("[]")
            }
        }
    }

    @Test
    fun loginListsAndStreamsCarryTheCredentialsIntact(): Unit = runBlocking(Dispatchers.IO) {
        server.dispatcher = panel
        server.start()
        val http = OkHttpClientAdapter()
        val client = XtreamClient(XtreamCredentials(server.url("/").toString().trimEnd('/'), username, password), http)

        val content = client.fetchContent(XtreamOutputFormat.M3U8)

        assertEquals(listOf("Harbor News"), content.channels.map { it.name })
        assertEquals(listOf("Quiet Water"), content.movies.map { it.name })
        assertEquals(listOf("Harbor Lights"), content.series.map { it.name })

        // Playing a movie: the panel splits the path into its segments.
        http.get(content.movies.first().streamUrl)

        val requests = generateSequence { server.takeRequest(1, TimeUnit.SECONDS) }.toList()
        val api = requests.filter { it.path!!.startsWith("/player_api.php") }
        val lists = setOf("get_live_categories", "get_live_streams", "get_vod_categories", "get_vod_streams", "get_series_categories", "get_series")
        assertTrue(lists.all { action -> api.any { query(it.path!!)["action"] == action } }, "login, then the six lists")
        for (request in api) {
            assertEquals(password, query(request.path!!)["password"], "the password arrives as typed")
            assertEquals(DEFAULT_IPTV_USER_AGENT, request.getHeader("User-Agent"))
        }
        val stream = requests.last { it.path!!.startsWith("/movie/") }
        assertEquals(listOf("movie", username, password, "5001.mp4"), segments(stream.path!!))
    }
}
