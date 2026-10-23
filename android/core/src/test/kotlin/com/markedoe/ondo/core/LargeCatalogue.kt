package com.markedoe.ondo.core

import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.net.HttpException
import com.markedoe.ondo.core.net.OkHttpClientAdapter
import com.markedoe.ondo.core.net.Urls
import java.io.File
import java.io.Writer

/**
 * Provider answers the size of large catalogues and guides, written to files (made-up names, example.com addresses).
 * One name in five is in Arabic script, as in multi-language catalogues: such text takes two bytes a character in
 * memory.
 */
object LargeCatalogue {
    private fun name(prefix: String, i: Int) = if (i % 5 == 0) "AR: فيلم رقم $i" else "$prefix $i (${1980 + i % 45})"

    private fun Writer.array(count: Int, entry: Writer.(Int) -> Unit) {
        write("[")
        for (i in 1..count) {
            if (i > 1) write(",")
            entry(i)
        }
        write("]")
    }

    /** The Xtream API answers (`player_api.php`, by action) for [live] channels, [movies] and [series]. */
    fun xtream(folder: File, live: Int, movies: Int, series: Int): Map<String, File> {
        folder.mkdirs()
        fun file(action: String, write: Writer.() -> Unit) = action to File(folder, "$action.json").also { f -> f.bufferedWriter().use { it.write() } }
        val categories = 200
        return mapOf(
            file("") {
                write("""{"user_info":{"username":"demo","password":"demo","auth":1,"status":"Active","exp_date":"1900000000","is_trial":"0","active_cons":"0","max_connections":"1","allowed_output_formats":["m3u8","ts"]},"server_info":{"url":"panel.example.com","port":"80","timezone":"UTC"}}""")
            },
            file("get_live_categories") { array(categories) { write("""{"category_id":"$it","category_name":"FR | Category $it","parent_id":0}""") } },
            file("get_vod_categories") { array(categories) { write("""{"category_id":"$it","category_name":"FR | Films $it","parent_id":0}""") } },
            file("get_series_categories") { array(categories) { write("""{"category_id":"$it","category_name":"FR | Series $it","parent_id":0}""") } },
            file("get_live_streams") {
                array(live) { i ->
                    write("""{"num":$i,"name":"${name("FR: Channel", i)} HD","stream_type":"live","stream_id":$i,"stream_icon":"http://img.example.com/logos/$i.png","epg_channel_id":"channel$i.example","added":"1700000000","is_adult":"0","category_id":"${i % categories + 1}","category_ids":[${i % categories + 1}],"custom_sid":"","tv_archive":${i % 7 / 6},"direct_source":"","tv_archive_duration":${i % 7 / 6 * 3}}""")
                }
            },
            file("get_vod_streams") {
                array(movies) { i ->
                    write("""{"num":$i,"name":"${name("Movie", i)}","title":"${name("Movie", i)}","year":"${1980 + i % 45}","stream_type":"movie","stream_id":$i,"stream_icon":"https://img.example.com/t/p/w600_and_h900_bestv2/poster$i.jpg","rating":"${i % 10}.${i % 7}","rating_5based":${i % 5}.5,"added":"${1600000000 + i}","is_adult":"0","category_id":"${i % categories + 1}","category_ids":[${i % categories + 1}],"container_extension":"mkv","custom_sid":"","direct_source":""}""")
                }
            },
            file("get_series") {
                array(series) { i ->
                    write("""{"num":$i,"name":"${name("Series", i)}","title":"${name("Series", i)}","year":"${1980 + i % 45}","stream_type":"series","series_id":$i,"cover":"https://img.example.com/t/p/w600_and_h900_bestv2/cover$i.jpg","plot":"A made-up story number $i about a family, a town and a long summer that changes everything for them, told over several seasons.","cast":"Actor One, Actor Two, Actor Three, Actor Four","director":"Director $i","genre":"Drama / Comedy","releaseDate":"${1980 + i % 45}-05-01","release_date":"${1980 + i % 45}-05-01","last_modified":"${1600000000 + i}","rating":"7","rating_5based":3.5,"backdrop_path":["https://img.example.com/t/p/w1280/backdrop$i.jpg"],"youtube_trailer":"","episode_run_time":"45","category_id":"${i % categories + 1}","category_ids":[${i % categories + 1}]}""")
                }
            },
        )
    }

    /** An M3U playlist of [entries] (channels, movies and episodes). */
    fun m3u(file: File, entries: Int): File {
        file.bufferedWriter().use { out ->
            out.write("#EXTM3U\n")
            for (i in 1..entries) {
                val group = when (i % 3) {
                    0 -> "FR | Channels ${i % 50}"
                    1 -> "VOD | Movies ${i % 50}"
                    else -> "SERIES | Shows ${i % 50}"
                }
                val title = if (i % 3 == 2) "${name("Show", i / 30)} S0${i % 9 + 1}E${i % 20 + 1}" else name("Title", i)
                out.write("#EXTINF:-1 tvg-id=\"channel$i.example\" tvg-name=\"$title\" tvg-logo=\"http://img.example.com/$i.png\" group-title=\"$group\",$title\n")
                out.write(
                    when (i % 3) {
                        0 -> "http://stream.example.com/live/demo/demo/$i.ts\n"
                        1 -> "http://stream.example.com/movie/demo/demo/$i.mkv\n"
                        else -> "http://stream.example.com/series/demo/demo/$i.mkv\n"
                    },
                )
            }
        }
        return file
    }

    /** An XMLTV guide: [channels] channels, [days] days of [perDay] programmes from [start] (epoch millis). */
    fun xmltv(file: File, channels: Int, days: Int, perDay: Int, start: Long): File {
        val format = java.text.SimpleDateFormat("yyyyMMddHHmmss Z").apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val step = 86_400_000L / perDay
        file.bufferedWriter().use { out ->
            out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv generator-info-name=\"test\">\n")
            for (c in 1..channels) out.write("<channel id=\"channel$c.example\"><display-name>Channel $c</display-name><icon src=\"http://img.example.com/$c.png\"/></channel>\n")
            for (c in 1..channels) {
                for (p in 0 until days * perDay) {
                    val begin = start + p * step
                    out.write("<programme start=\"${format.format(begin)}\" stop=\"${format.format(begin + step)}\" channel=\"channel$c.example\">")
                    out.write("<title lang=\"fr\">Programme $p of channel $c</title><desc lang=\"fr\">A made-up description of programme $p, long enough to look like the ones providers send with their guides.</desc><category lang=\"fr\">Magazine</category></programme>\n")
                }
            }
            out.write("</tv>\n")
        }
        return file
    }
}

/**
 * Serves files by URL as [OkHttpClientAdapter] serves answers — read whole into memory by [get], copied by [download] —
 * without a server holding the bodies in this process's memory. Xtream API calls are routed by their `action`.
 */
class FileHttp(private val routes: Map<String, File>) : HttpClient {
    private fun file(url: String): File {
        val parsed = Urls.parse(url)
        val key = if (parsed?.path?.endsWith("player_api.php") == true) {
            Urls.queryItems(parsed.rawQuery).firstOrNull { it.first == "action" }?.second ?: ""
        } else {
            url
        }
        return routes[key] ?: throw HttpException.BadStatus(404)
    }

    override suspend fun get(url: String, headers: Map<String, String>): ByteArray =
        OkHttpClientAdapter.readLimited(file(url).inputStream(), OkHttpClientAdapter.MAX_BODY_SIZE)

    override suspend fun download(url: String, headers: Map<String, String>, file: File) =
        OkHttpClientAdapter.copyLimited(file(url).inputStream(), file, OkHttpClientAdapter.MAX_BODY_SIZE)
}
