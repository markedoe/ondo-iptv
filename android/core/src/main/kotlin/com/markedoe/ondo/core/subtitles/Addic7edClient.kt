package com.markedoe.ondo.core.subtitles

import com.markedoe.ondo.core.TextNormalizer
import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.net.HttpException
import java.net.URLEncoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/** Addic7ed's series subtitles, through the public Gestdown API (no account), as on the Apple apps. Episodes only. */
class Addic7edClient(private val userAgent: String, private val http: HttpClient) : SubtitleProvider {
    override val service = SubtitleService.ADDIC7ED

    private suspend fun get(path: String): ByteArray = http.get(BASE + path, mapOf("User-Agent" to userAgent, "Accept" to "application/json"))

    private suspend fun json(path: String): JsonElement? = try {
        SubtitleHttp.parse(service, SubtitleHttp.translating(service) { get(path) })
    } catch (e: HttpException.BadStatus) {
        if (e.code == 404) null else throw e
    }

    private fun segment(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    override suspend fun search(query: SubtitleQuery): List<OnlineSubtitle> {
        val season = query.season ?: return emptyList()
        val number = query.episode ?: return emptyList()
        val shows = (json("/shows/search/${segment(query.title)}").obj()?.get("shows") as? JsonArray).orEmpty().mapNotNull { it.obj() }
        val wanted = TextNormalizer.normalize(query.title)
        val show = shows.firstOrNull { TextNormalizer.normalize(it.string("name").orEmpty()) == wanted } ?: shows.firstOrNull() ?: return emptyList()
        val showId = show.string("id") ?: return emptyList()
        val results = ArrayList<OnlineSubtitle>()
        for (code in query.languages.take(2)) {
            val language = SubtitleLanguages.englishName(code) ?: continue
            val episodes = (json("/shows/${segment(showId)}/$season/${segment(language)}").obj()?.get("episodes") as? JsonArray).orEmpty().mapNotNull { it.obj() }
            for (episode in episodes.filter { it.int("number") == number }) {
                for (subtitle in (episode["subtitles"] as? JsonArray).orEmpty().mapNotNull { it.obj() }) {
                    if (subtitle.bool("completed") == false) continue
                    val uri = subtitle.string("downloadUri")?.takeIf { it.startsWith("/") } ?: continue
                    results += OnlineSubtitle(
                        service = service,
                        reference = uri,
                        language = code,
                        release = listOfNotNull(show.string("name"), query.episodeCode, subtitle.string("version")).joinToString(" · "),
                        downloads = subtitle.int("downloadCount"),
                        hearingImpaired = subtitle.bool("hearingImpaired") ?: false,
                    )
                }
            }
        }
        return results
    }

    override suspend fun download(subtitle: OnlineSubtitle, query: SubtitleQuery): DownloadedSubtitle {
        val data = SubtitleHttp.translating(service) { get(subtitle.reference) }
        val name = listOfNotNull(query.title, query.episodeCode, subtitle.language).joinToString(".") + ".srt"
        return DownloadedSubtitle(name, data)
    }

    companion object {
        const val BASE = "https://api.gestdown.info"
    }
}
