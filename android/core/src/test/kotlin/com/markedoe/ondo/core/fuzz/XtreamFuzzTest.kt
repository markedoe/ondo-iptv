package com.markedoe.ondo.core.fuzz

import com.markedoe.ondo.core.Fixtures
import com.markedoe.ondo.core.MockHttpClient
import com.markedoe.ondo.core.catchup.CatchupUrlBuilder
import com.markedoe.ondo.core.epg.EpgIndex
import com.markedoe.ondo.core.library.PlaybackFactory
import com.markedoe.ondo.core.library.PlaylistLoader
import com.markedoe.ondo.core.library.SearchEngine
import com.markedoe.ondo.core.model.Playlist
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.PlaylistSource
import com.markedoe.ondo.core.model.XtreamCredentials
import com.markedoe.ondo.core.model.XtreamOutputFormat
import com.markedoe.ondo.core.xtream.XtreamClient
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Ignore
import org.junit.Test

/**
 * Every `player_api.php` answer the client decodes, damaged field by field: the client and the loader fail only
 * with the documented exceptions, and what they return keeps the invariants the app's lists rely on (one id per
 * row: Compose stops the app on a key used twice).
 */
class XtreamFuzzTest {
    private val credentials = XtreamCredentials("http://example.com:8080", "user", "pass") // leak-guard: allow

    private val fixtures = mapOf(
        "api:" to "xtream_auth.json",
        "api:get_live_categories" to "xtream_live_categories.json",
        "api:get_live_streams" to "xtream_live_streams.json",
        "api:get_vod_categories" to "xtream_vod_categories.json",
        "api:get_vod_streams" to "xtream_vod_streams.json",
        "api:get_series_categories" to "xtream_series_categories.json",
        "api:get_series" to "xtream_series.json",
        "api:get_series_info" to "xtream_series_info.json",
        "api:get_vod_info" to "xtream_vod_info.json",
        "api:get_short_epg" to "xtream_short_epg.json",
        "api:get_simple_data_table" to "xtream_short_epg.json",
    ).mapValues { (_, name) -> Fixtures.bytes(name) }

    private fun http(overrides: Map<String, ByteArray> = emptyMap()) = MockHttpClient().apply {
        routes.putAll(fixtures)
        routes.putAll(overrides)
    }

    private fun client(http: MockHttpClient) = XtreamClient(credentials, http, parsingDispatcher = Dispatchers.Unconfined, clock = { 0L })

    private fun <T> List<T>.assertDistinctBy(label: String, key: (T) -> Any?) {
        val keys = map(key)
        assertEquals(keys.distinct(), keys, "$label: an id listed twice")
    }

    // Findings

    @Test
    fun aCategoryListedTwiceIsListedOnce() = runBlocking {
        val categories = """[{"category_id":"1","category_name":"News"},{"category_id":"1","category_name":"News again"},""" +
            """{"category_id":1,"category_name":"Same id as a number"},{"category_id":"2","category_name":"Sport"}]"""
        val http = http(
            mapOf(
                "api:get_live_categories" to categories.encodeToByteArray(),
                "api:get_vod_categories" to categories.encodeToByteArray(),
                "api:get_series_categories" to categories.encodeToByteArray(),
            ),
        )
        val content = client(http).fetchContent(XtreamOutputFormat.M3U8)
        content.liveCategories.assertDistinctBy("live categories") { it.id }
        content.movieCategories.assertDistinctBy("movie categories") { it.id }
        content.seriesCategories.assertDistinctBy("series categories") { it.id }
        assertEquals("News", content.liveCategories.first { it.id == "live-1" }.name, "the first one is kept")
    }

    @Test
    fun anEpisodeListedTwiceInASeasonIsListedOnce() = runBlocking {
        val episode = """{"id":"5","episode_num":1,"title":"Pilot","container_extension":"mkv"}"""
        val other = """{"id":"6","episode_num":2,"title":"Second","container_extension":"mkv"}"""
        for (episodes in listOf(
            """{"1":[$episode,$episode,$other],"2":[$episode]}""",
            """[[$episode,$other,$episode],[{"id":"5","season":2}]]""",
            """[$episode,$other,$episode]""",
        )) {
            val http = http(mapOf("api:get_series_info" to """{"episodes":$episodes}""".encodeToByteArray()))
            val details = client(http).seriesDetails(1)
            for (season in details.seasons) season.episodes.assertDistinctBy("season ${season.number} of $episodes") { it.id }
            assertEquals(listOf("5", "6"), details.seasons.first().episodes.map { it.id }, episodes)
        }
    }

    /**
     * `XtreamClient.getJson` hands the answer to kotlinx.serialization as is: 100 000 `[` throw StackOverflowError
     * out of `authenticate` (and of every list). Left to the streaming rewrite of `getJson`, which should refuse
     * answers nesting deeper than `MAX_JSON_DEPTH` (`jsonNestsDeeperThan`, as `parseJsonOrNull` does).
     */
    @Ignore("Fails until XtreamClient.getJson refuses deeply nested answers (streaming rewrite in progress)")
    @Test(timeout = 30_000)
    fun aDeeplyNestedAnswerIsAnInvalidResponse() {
        for (route in listOf("api:", "api:get_live_streams", "api:get_series_info")) {
            val http = http(mapOf(route to "[".repeat(100_000).encodeToByteArray()))
            val client = client(http)
            withinContract("authenticate $route", { route }) { runBlocking { client.authenticate() } }
            withinContract("fetchContent $route", { route }) { runBlocking { client.fetchContent(XtreamOutputFormat.TS) } }
            withinContract("seriesDetails $route", { route }) { runBlocking { client.seriesDetails(1) } }
        }
    }

    // Fuzzing

    /** Raw number literals that `JsonPrimitive` cannot hold, put in place of their placeholders. */
    private val rawLiterals = mapOf(
        "\"#raw:1e999\"" to "1e999",
        "\"#raw:-1e999\"" to "-1e999",
        "\"#raw:huge\"" to "123456789012345678901234567890",
        "\"#raw:-0\"" to "-0",
        "\"#raw:1E+2\"" to "1E+2",
    )

    private val variants: List<JsonElement> = listOf(
        JsonPrimitive(""), JsonPrimitive("x"), JsonPrimitive("0"), JsonPrimitive("-1"), JsonPrimitive("1.5"), JsonPrimitive("8,8"),
        JsonPrimitive("1e999"), JsonPrimitive("NaN"), JsonPrimitive("Infinity"), JsonPrimitive(" 7 "), JsonPrimitive("4102444800"),
        JsonPrimitive("99999999999999999999999"), JsonPrimitive("true"), JsonPrimitive("yes"), JsonPrimitive("active"),
        JsonPrimitive("Banned"), JsonPrimitive(0), JsonPrimitive(-1), JsonPrimitive(1), JsonPrimitive(Int.MAX_VALUE),
        JsonPrimitive(Int.MIN_VALUE), JsonPrimitive(Long.MAX_VALUE), JsonPrimitive(Long.MIN_VALUE), JsonPrimitive(1.5),
        JsonPrimitive(1e308), JsonPrimitive(-1e308), JsonPrimitive(4.9e-324), JsonPrimitive(true), JsonPrimitive(false), JsonNull,
        JsonArray(emptyList()), JsonObject(emptyMap()), JsonArray(listOf(JsonPrimitive("a"))), JsonArray(listOf(JsonPrimitive(1))),
        JsonArray(listOf(JsonNull, JsonPrimitive("a"))), JsonObject(mapOf("a" to JsonPrimitive(1))),
        JsonObject(mapOf("1" to JsonObject(mapOf("id" to JsonPrimitive("7"))))), JsonPrimitive("\uD800"),
        JsonPrimitive("a".repeat(100_000)), JsonPrimitive("S01E01 - ".repeat(2_000)), JsonPrimitive("http://example.com/a b.ts"),
        JsonPrimitive("../../x"), JsonPrimitive("%zz"), JsonPrimitive("Europe/Nowhere"), JsonPrimitive("+99:00"),
        JsonPrimitive("https://image.tmdb.org/t/p/w1280"), JsonPrimitive("\u0000"), JsonPrimitive("\u202Eevil"),
        JsonPrimitive("#raw:1e999"), JsonPrimitive("#raw:-1e999"), JsonPrimitive("#raw:huge"), JsonPrimitive("#raw:-0"), JsonPrimitive("#raw:1E+2"),
        (1..50).fold(JsonArray(emptyList()) as JsonElement) { inner, _ -> JsonArray(listOf(inner)) },
    )

    private fun mutate(element: JsonElement, fuzz: Fuzz): JsonElement = when {
        element is JsonObject && element.isNotEmpty() && fuzz.chance(80) -> {
            val map = LinkedHashMap(element)
            val key = fuzz.pick(map.keys.toList())
            when (fuzz.int(8)) {
                0 -> map.remove(key)
                1 -> map[key] = fuzz.pick(variants)
                2 -> map[key] = JsonArray(listOf(map.getValue(key)))
                else -> map[key] = mutate(map.getValue(key), fuzz)
            }
            JsonObject(map)
        }
        element is JsonArray && element.isNotEmpty() && fuzz.chance(80) -> {
            val list = element.toMutableList()
            val index = fuzz.int(list.size)
            when (fuzz.int(8)) {
                0 -> list.removeAt(index)
                1 -> list.add(index, list[index])
                2 -> list[index] = fuzz.pick(variants)
                3 -> return JsonObject(list.withIndex().associate { (i, value) -> (if (fuzz.chance(80)) "$i" else "x$i") to value })
                else -> list[index] = mutate(list[index], fuzz)
            }
            JsonArray(list)
        }
        else -> fuzz.pick(variants)
    }

    private fun encode(element: JsonElement): ByteArray {
        var text = Json.encodeToString(JsonElement.serializer(), element)
        for ((placeholder, literal) in rawLiterals) text = text.replace(placeholder, literal)
        return text.encodeToByteArray()
    }

    /** A damaged answer: fields swapped, removed or duplicated, or the whole body replaced. */
    private fun damaged(original: ByteArray, fuzz: Fuzz): ByteArray = when (fuzz.int(20)) {
        0 -> fuzz.pick("[]", "{}", "null", "\"x\"", "0", "true", "", " ", "<html><body>502 Bad Gateway</body></html>", "{\"user_info\":", "\uFEFF{}").encodeToByteArray()
        1 -> original.copyOf(fuzz.int(original.size))
        2 -> fuzz.mutate(original)
        3 -> gzip(original).let { if (fuzz.chance(50)) it else it.copyOf(fuzz.int(it.size)) }
        4 -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + original
        else -> {
            var element = Json.parseToJsonElement(original.decodeToString())
            repeat(1 + fuzz.int(4)) { element = mutate(element, fuzz) }
            encode(element)
        }
    }

    @Test(timeout = 120_000)
    fun damagedAnswersFailOnlyAsDocumented() {
        val fuzz = Fuzz(seed = 4242)
        val routes = fixtures.keys.toList()
        repeat(CASES) { case ->
            val damagedRoutes = List(1 + fuzz.int(2)) { fuzz.pick(routes) }.distinct()
            val overrides = damagedRoutes.associateWith { damaged(fixtures.getValue(it), fuzz) }
            val input = { "case $case: " + overrides.entries.joinToString { (route, body) -> "$route=${describe(body)}" } }
            val http = http(overrides)
            val client = client(http)
            withinContract("authenticate", input) { runBlocking { client.authenticate() } }
            val content = withinContract("fetchContent", input) { runBlocking { client.fetchContent(fuzz.pick(XtreamOutputFormat.entries)) } }
            if (content != null) checkContent(content, input)
            val details = withinContract("seriesDetails", input) { runBlocking { client.seriesDetails(fuzz.pick(1, -1, Int.MAX_VALUE)) } }
            if (details != null) {
                details.seasons.assertDistinctBy("seasons, " + input()) { it.number }
                for (season in details.seasons) season.episodes.assertDistinctBy("season ${season.number}, " + input()) { it.id }
                neverThrows("episodes", input) { details.seasons.flatMap { it.episodes }.forEach { it.code } }
            }
            withinContract("movieDetails", input) { runBlocking { client.movieDetails(1) } }
            for (epg in listOf("shortEpg", "fullEpg")) {
                val programs = withinContract(epg, input) {
                    runBlocking { if (epg == "shortEpg") client.shortEpg(1, "TV1.fr") else client.fullEpg(1, "TV1.fr") }
                }
                if (programs != null) neverThrows("guide of $epg", input) { EpgIndex(programs).current("TV1.fr", 1_760_033_000_000) }
            }
            withinContract("PlaylistLoader", input) {
                val playlist = Playlist(name = "p", source = PlaylistSource.Xtream(credentials), xtreamForceM3u = fuzz.chance(10))
                runBlocking { PlaylistLoader(http, Dispatchers.Unconfined, Dispatchers.Unconfined, clock = { 0L }).load(playlist) }
            }
        }
    }

    /** What the app does with a catalogue right after loading it. */
    private fun checkContent(content: PlaylistContent, input: () -> String) {
        content.channels.assertDistinctBy("channels, " + input()) { it.id }
        content.movies.assertDistinctBy("movies, " + input()) { it.id }
        content.series.assertDistinctBy("series, " + input()) { it.id }
        content.liveCategories.assertDistinctBy("live categories, " + input()) { it.id }
        content.movieCategories.assertDistinctBy("movie categories, " + input()) { it.id }
        content.seriesCategories.assertDistinctBy("series categories, " + input()) { it.id }
        neverThrows("catalogue", input) {
            val factory = PlaybackFactory(Playlist(name = "p", source = PlaylistSource.Xtream(credentials)), content.account)
            for (channel in content.channels) {
                factory.item(channel)
                factory.catchupItem(channel, 1_760_000_000_000, 1_760_003_600_000, now = 1_760_010_000_000)
                CatchupUrlBuilder.isAvailable(channel, 0L)
            }
            for (movie in content.movies) factory.item(movie)
            SearchEngine(content).search("a")
            content.liveCategories.forEach { it.looksAdult }
        }
        assertTrue(content.channels.all { it.categoryId in content.liveCategories.map { c -> c.id } }, "a channel without its category, " + input())
    }

    private companion object {
        const val CASES = 1_500
    }
}
