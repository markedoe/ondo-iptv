package com.markedoe.ondo.core.subtitles

import com.markedoe.ondo.core.TextNormalizer
import com.markedoe.ondo.core.net.HttpClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * SubSource API (api.subsource.net), as on the Apple apps: find the title, then its subtitles in each preferred
 * language (named in English, "french"). The files come zipped.
 */
class SubSourceClient(private val apiKey: String, private val http: HttpClient) : SubtitleProvider {
    override val service = SubtitleService.SUBSOURCE

    private suspend fun get(path: String, items: List<Pair<String, String?>> = emptyList()): JsonElement {
        val tail = SubtitleHttp.query(items).let { if (it.isEmpty()) "" else "?$it" }
        val data = SubtitleHttp.translating(service) { http.get(BASE + path + tail, mapOf("X-API-Key" to apiKey, "Accept" to "application/json")) }
        return SubtitleHttp.parse(service, data)
    }

    /** Checks the API key with a title search (401 or 403 when the service refuses it). */
    suspend fun checkKey(): CredentialCheck = try {
        get("/movies/search", listOf("searchType" to "text", "q" to "The Matrix", "type" to "movie"))
        CredentialCheck.ACCEPTED
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        CredentialCheck.failure(e)
    }

    data class Title(val movieId: Int, val title: String, val releaseYear: Int?, val season: Int?)

    override suspend fun search(query: SubtitleQuery): List<OnlineSubtitle> {
        val titles = (get(
            "/movies/search",
            listOf("searchType" to "text", "q" to query.title, "type" to if (query.isEpisode) "series" else "movie", "season" to query.season?.toString()),
        ).obj()?.get("data") as? JsonArray).orEmpty().mapNotNull { element ->
            val title = element.obj() ?: return@mapNotNull null
            Title(title.int("movieId") ?: return@mapNotNull null, title.string("title").orEmpty(), title.int("releaseYear"), title.int("season"))
        }
        val title = bestMatch(titles, query) ?: return emptyList()
        val results = ArrayList<OnlineSubtitle>()
        // Two languages at most: one request each, and the service counts them.
        for (code in query.languages.take(2)) {
            val name = SubtitleLanguages.englishName(code)?.lowercase() ?: continue
            val subtitles = (get(
                "/subtitles",
                listOf("movieId" to title.movieId.toString(), "language" to name, "limit" to "30", "sort" to "popular"),
            ).obj()?.get("data") as? JsonArray).orEmpty()
            for (element in subtitles) {
                val subtitle = element.obj() ?: continue
                val id = subtitle.int("subtitleId") ?: continue
                val release = (subtitle["releaseInfo"] as? JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }?.joinToString(" · ").orEmpty()
                results += OnlineSubtitle(
                    service = service,
                    reference = id.toString(),
                    language = SubtitleLanguages.code(subtitle.string("language") ?: name) ?: code,
                    release = release,
                    downloads = subtitle.int("downloads"),
                    hearingImpaired = subtitle.bool("hearingImpaired") ?: false,
                )
            }
        }
        // A season's page lists every episode: this one's when the releases name it.
        query.episodeCode?.let { code ->
            val episode = results.filter { it.release.uppercase().contains(code) }
            if (episode.isNotEmpty()) return episode
        }
        return results
    }

    override suspend fun download(subtitle: OnlineSubtitle, query: SubtitleQuery): DownloadedSubtitle {
        val id = subtitle.reference.toIntOrNull() ?: throw SubtitleSearchException.ServiceError(service, "unknown file")
        val data = SubtitleHttp.translating(service) { http.get("$BASE/subtitles/$id/download", mapOf("X-API-Key" to apiKey)) }
        return SubtitleArchive.pick(data, query.episodeCode)
    }

    companion object {
        const val BASE = "https://api.subsource.net/api/v1"

        /** The same season for a series; then the release year when known; else the exact title; else the first answer. */
        fun bestMatch(titles: List<Title>, query: SubtitleQuery): Title? {
            var candidates = titles
            query.season?.let { season -> candidates.filter { it.season == season }.takeIf { it.isNotEmpty() }?.let { candidates = it } }
            query.year?.let { year -> candidates.firstOrNull { it.releaseYear == year }?.let { return it } }
            val wanted = TextNormalizer.normalize(query.title)
            return candidates.firstOrNull { TextNormalizer.normalize(it.title) == wanted } ?: candidates.firstOrNull()
        }
    }
}
