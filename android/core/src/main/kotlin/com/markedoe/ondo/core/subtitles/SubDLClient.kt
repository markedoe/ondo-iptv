package com.markedoe.ondo.core.subtitles

import com.markedoe.ondo.core.net.HttpClient
import kotlinx.serialization.json.JsonArray

/** SubDL API, as on the Apple apps: a search with the viewer's API key; the files come zipped from its download host. */
class SubDLClient(private val apiKey: String, private val http: HttpClient) : SubtitleProvider {
    override val service = SubtitleService.SUBDL

    override suspend fun search(query: SubtitleQuery): List<OnlineSubtitle> {
        val items = listOf(
            "api_key" to apiKey,
            // By TMDb's id when the provider gives it; by name otherwise.
            "tmdb_id" to query.tmdbId?.toString(),
            "film_name" to query.title.takeIf { query.tmdbId == null },
            "type" to if (query.isEpisode) "tv" else "movie",
            "year" to query.year?.toString()?.takeIf { !query.isEpisode && query.tmdbId == null },
            "season_number" to query.season?.toString(),
            "episode_number" to query.episode?.toString(),
            "languages" to query.languages.joinToString(",") { it.uppercase() },
            "subs_per_page" to "30",
        )
        val data = SubtitleHttp.translating(service) { http.get("$BASE?" + SubtitleHttp.query(items), mapOf("Accept" to "application/json")) }
        val answer = SubtitleHttp.parse(service, data).obj()
        if (answer.bool("status") == false) {
            val message = (answer.string("error") ?: answer.string("message") ?: "").lowercase()
            if ("api" in message || "key" in message) throw SubtitleSearchException.InvalidCredentials(service)
            // "can't find movie or tv": no result, not a failure.
            return emptyList()
        }
        return (answer?.get("subtitles") as? JsonArray).orEmpty().mapNotNull { element ->
            val subtitle = element.obj() ?: return@mapNotNull null
            val path = subtitle.string("url")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val language = subtitle.string("language") ?: subtitle.string("lang") ?: ""
            OnlineSubtitle(
                service = service,
                reference = path,
                language = SubtitleLanguages.code(language) ?: language.lowercase(),
                release = subtitle.string("release_name") ?: subtitle.string("name") ?: path,
                hearingImpaired = subtitle.bool("hi") ?: false,
            )
        }
    }

    override suspend fun download(subtitle: OnlineSubtitle, query: SubtitleQuery): DownloadedSubtitle {
        // Only SubDL's own download host: the reference came from the service's answer.
        val address = if (subtitle.reference.startsWith("https://dl.subdl.com/")) subtitle.reference else DOWNLOADS + "/" + subtitle.reference.trimStart('/')
        val data = SubtitleHttp.translating(service) { http.get(address) }
        return SubtitleArchive.pick(data, query.episodeCode)
    }

    /** Checks the API key with a search: the service answers a refused key with an error that names it. */
    suspend fun checkKey(): CredentialCheck = try {
        search(SubtitleQuery("The Matrix", 1999, languages = listOf("en")))
        CredentialCheck.ACCEPTED
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        CredentialCheck.failure(e)
    }

    companion object {
        const val BASE = "https://api.subdl.com/api/v1/subtitles"
        const val DOWNLOADS = "https://dl.subdl.com"
    }
}
