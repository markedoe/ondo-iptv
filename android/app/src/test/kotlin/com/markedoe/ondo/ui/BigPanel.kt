package com.markedoe.ondo.ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A fictional Xtream panel of real size, with what real panels send: thousands of channels, movies and series, a
 * stream or a category listed twice side by side, categories with nothing in them or never listed, missing and
 * broken pictures, Arabic names with direction marks, emoji, an empty name, a very long name and plot, odd years and
 * ratings, language tags ("|FR|") on most categories. Everything at panel.example.com.
 */
object BigPanel {
    const val SERVER = "http://panel.example.com"
    const val USERNAME = "viewer"
    const val PASSWORD = "secret" // leak-guard: allow

    fun api(action: String? = null, parameters: String = "") =
        "$SERVER/player_api.php?username=$USERNAME&password=$PASSWORD" + (action?.let { "&action=$it" } ?: "") + parameters

    private val tags = listOf("FR", "EN", "AR", "DE", "ES", "TR", "IT", "PT", "NL", "PL")
    private val arabic = listOf("أخبار", "رياضة", "أفلام", "مسلسلات", "أطفال", "وثائقي")

    /** A category name with a language tag, sometimes in Arabic with a right-to-left mark. */
    private fun categoryName(kind: String, index: Int): String {
        val tag = tags[index % tags.size]
        return when (index % 7) {
            0 -> "|$tag| ${arabic[index % arabic.size]} ‏$kind $index"
            1 -> "$tag - $kind $index"
            2 -> "[$tag] $kind $index 🎬"
            3 -> "4K |$tag| $kind $index"
            else -> "|$tag| $kind $index"
        }
    }

    /** [count] categories, the second one listed twice, and the last [empty] never used by a stream. */
    private fun categories(kind: String, count: Int): JsonArray = buildJsonArray {
        for (index in 1..count) {
            val category = buildJsonObject {
                put("category_id", index.toString())
                put("category_name", categoryName(kind, index))
                put("parent_id", 0)
            }
            add(category)
            if (index == 2) add(category)
        }
    }

    /** The category a stream is in: mostly listed ones (the first [used]), sometimes none, empty or unknown. */
    private fun categoryOf(index: Int, used: Int): JsonElement = when (index % 97) {
        0 -> JsonNull
        1 -> JsonPrimitive("")
        2 -> JsonPrimitive("9999")
        else -> JsonPrimitive(((index * 31) % used + 1).toString())
    }

    private fun icon(index: Int): JsonElement = when (index % 5) {
        0 -> JsonPrimitive("")
        1 -> JsonNull
        2 -> JsonPrimitive("images/$index.png")
        3 -> JsonPrimitive("javascript:void(0)")
        else -> JsonPrimitive("$SERVER/images/$index.jpg")
    }

    private fun title(kind: String, index: Int): String = when (index % 211) {
        0 -> ""
        1 -> "‫${arabic[index % arabic.size]} $index‬"
        2 -> "🎬🍿 $kind $index 👨‍👩‍👧"
        3 -> "$kind $index " + "x".repeat(20_000)
        else -> "$kind ${index.toString(36).uppercase()} $index"
    }

    const val LIVE_CATEGORIES = 160
    const val MOVIE_CATEGORIES = 260
    const val SERIES_CATEGORIES = 90
    const val CHANNELS = 4_000
    const val MOVIES = 8_000
    const val SERIES = 2_000

    /** Categories used by streams; the others are empty. */
    private const val USED_SHARE = 0.75

    fun liveStreams(): JsonArray = buildJsonArray {
        val used = (LIVE_CATEGORIES * USED_SHARE).toInt()
        for (index in 1..CHANNELS) {
            val stream = buildJsonObject {
                put("num", index)
                put("name", title("Channel", index))
                put("stream_type", "live")
                put("stream_id", 10_000 + index)
                put("stream_icon", icon(index))
                put("epg_channel_id", if (index % 3 == 0) JsonNull else JsonPrimitive("ch$index.example"))
                put("category_id", categoryOf(index, used))
                put("tv_archive", index % 4)
                put("tv_archive_duration", if (index % 4 == 0) JsonPrimitive("abc") else JsonPrimitive(index % 8))
            }
            add(stream)
            // The same stream listed twice, side by side.
            if (index % 50 == 0) add(stream)
        }
    }

    /** Movies; most released this year or the last one (Home's carousel and new movies are full). */
    fun vodStreams(plot: String = "A fictional film."): JsonArray = buildJsonArray {
        val used = (MOVIE_CATEGORIES * USED_SHARE).toInt()
        for (index in 1..MOVIES) {
            val stream = buildJsonObject {
                put("num", index)
                put("name", title("Movie", index) + if (index % 13 == 0) " (2024)" else "")
                put("stream_type", "movie")
                put("stream_id", 100_000 + index)
                put("stream_icon", icon(index))
                put("category_id", categoryOf(index, used))
                put(
                    "rating",
                    when (index % 6) {
                        0 -> JsonPrimitive("N/A")
                        1 -> JsonPrimitive(-2)
                        2 -> JsonPrimitive(97.5)
                        3 -> JsonNull
                        else -> JsonPrimitive("${index % 10}.${index % 7}")
                    },
                )
                put("rating_5based", index % 5)
                // Added in the future, long ago, or as text.
                put("added", when (index % 4) { 0 -> JsonPrimitive("4102444800"); 1 -> JsonPrimitive("abc"); else -> JsonPrimitive(1_700_000_000 + index) })
                put("container_extension", if (index % 9 == 0) "mkv" else "mp4")
                put(
                    "year",
                    when (index % 5) {
                        0 -> JsonPrimitive("2025")
                        1 -> JsonPrimitive(2024)
                        2 -> JsonPrimitive(0)
                        3 -> JsonNull
                        else -> JsonPrimitive(2025)
                    },
                )
                put("plot", if (index == 1) plot else "Plot of movie $index.")
            }
            add(stream)
            if (index % 40 == 0) add(stream)
        }
    }

    fun series(): JsonArray = buildJsonArray {
        val used = (SERIES_CATEGORIES * USED_SHARE).toInt()
        for (index in 1..SERIES) {
            val show = buildJsonObject {
                put("num", index)
                put("name", title("Series", index))
                put("series_id", 500_000 + index)
                put("cover", icon(index))
                put("plot", "Plot of series $index.")
                put("cast", "Ana Lima, Ana Lima, José Silva, Jose Silva, ${arabic[index % arabic.size]}")
                put("director", "Kim Park / Kim Park")
                put("genre", if (index % 2 == 0) "Drama, Drama" else "Action & Adventure, Adventure")
                put("releaseDate", if (index % 3 == 0) "2025-02-30" else "2024")
                put("last_modified", 1_700_000_000 + index)
                put("rating", if (index % 4 == 0) JsonPrimitive("N/A") else JsonPrimitive(index % 10))
                put("category_id", categoryOf(index, used))
                put("year", if (index % 2 == 0) JsonPrimitive("2025") else JsonPrimitive(2024))
            }
            add(show)
            if (index % 30 == 0) add(show)
        }
    }

    /** Seasons listed out of order, an episode twice, two episodes with the same number. */
    fun seriesInfo(seriesId: Int): String = buildJsonObject {
        put("info", buildJsonObject { put("name", "Series $seriesId"); put("cast", "Ana Lima, Ana Lima"); put("genre", "Drama, Drama") })
        put(
            "seasons",
            buildJsonArray {
                add(buildJsonObject { put("season_number", 2); put("name", "Season 2") })
                add(buildJsonObject { put("season_number", 1); put("name", "Season 1") })
                add(buildJsonObject { put("season_number", 1); put("name", "Season 1 again") })
            },
        )
        put(
            "episodes",
            buildJsonObject {
                for (season in listOf(2, 1, 0)) {
                    put(
                        season.toString(),
                        buildJsonArray {
                            for (episode in 1..12) {
                                val item = buildJsonObject {
                                    put("id", "${seriesId}0$season$episode")
                                    put("episode_num", if (episode == 3) 2 else episode)
                                    put("title", "Episode $episode")
                                    put("container_extension", "mkv")
                                }
                                add(item)
                                if (episode == 5) add(item)
                            }
                        },
                    )
                }
            },
        )
    }.toString()

    /** Every answer of the panel: the account, the six lists, and the details of a few series. */
    fun answers(moviePlot: String = "A fictional film."): Map<String, String> {
        val answers = HashMap<String, String>()
        answers[api()] = """{"user_info":{"auth":1,"status":"Active","username":"$USERNAME","allowed_output_formats":["m3u8","ts"]},"server_info":{"url":"panel.example.com","timezone":"Europe/Paris"}}"""
        answers[api("get_live_categories")] = categories("Live", LIVE_CATEGORIES).toString()
        answers[api("get_vod_categories")] = categories("Movies", MOVIE_CATEGORIES).toString()
        answers[api("get_series_categories")] = categories("Series", SERIES_CATEGORIES).toString()
        answers[api("get_live_streams")] = liveStreams().toString()
        answers[api("get_vod_streams")] = vodStreams(moviePlot).toString()
        answers[api("get_series")] = series().toString()
        for (index in 1..SERIES) {
            val id = 500_000 + index
            answers[api("get_series_info", "&series_id=$id")] = seriesInfo(id)
        }
        return answers
    }
}
