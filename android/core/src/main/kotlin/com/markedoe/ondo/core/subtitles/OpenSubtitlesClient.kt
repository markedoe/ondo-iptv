package com.markedoe.ondo.core.subtitles

import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.net.HttpException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * OpenSubtitles.com REST API (v1), as on the Apple apps. The API key identifies the app; an optional account raises the
 * daily download limit (5 per address and day without one). Signing in is rate limited by the service, so the session
 * is kept as long as this client and opened only for a download, never for a search.
 */
class OpenSubtitlesClient(
    private val apiKey: String,
    username: String? = null,
    password: String? = null,
    private val userAgent: String,
    private val http: HttpClient,
) : SubtitleProvider {
    override val service = SubtitleService.OPEN_SUBTITLES
    private val username = username?.takeIf { it.isNotEmpty() }
    private val password = password?.takeIf { it.isNotEmpty() }
    private var session: Pair<String, String>? = null

    /** After a refused login, no new attempt until the credentials change (the service asks for it). */
    private var loginRefused = false
    private val lock = Mutex()

    private fun headers(json: Boolean = false): Map<String, String> = buildMap {
        put("Api-Key", apiKey)
        put("User-Agent", userAgent)
        put("Accept", "application/json")
        if (json) put("Content-Type", "application/json")
        session?.let { put("Authorization", "Bearer ${it.first}") }
    }

    private fun url(path: String, query: List<Pair<String, String?>> = emptyList()): String {
        // Sorted, lower case, no defaults: the service redirects other forms.
        val items = query.filter { it.second != null }.sortedBy { it.first }
        val tail = if (items.isEmpty()) "" else "?" + SubtitleHttp.query(items)
        return "https://${session?.second ?: HOST}/api/v1$path$tail"
    }

    override suspend fun search(query: SubtitleQuery): List<OnlineSubtitle> {
        val items = buildList<Pair<String, String?>> {
            add("languages" to query.languages.map { it.lowercase() }.sorted().joinToString(","))
            val id = query.tmdbId
            if (query.isEpisode) {
                if (id != null) add("parent_tmdb_id" to id.toString()) else add("query" to query.title.lowercase())
                add("type" to "episode")
                add("season_number" to query.season.toString())
                add("episode_number" to query.episode.toString())
            } else {
                if (id != null) add("tmdb_id" to id.toString()) else add("query" to query.title.lowercase())
                add("type" to "movie")
                if (id == null) add("year" to query.year?.toString())
            }
        }
        val data = SubtitleHttp.translating(service) { http.get(url("/subtitles", items), headers()) }
        val answer = SubtitleHttp.parse(service, data).obj()
        val list = answer?.get("data") as? JsonArray ?: throw SubtitleSearchException.ServiceError(service, "unreadable answer")
        return list.mapNotNull { item ->
            val attributes = item.obj()?.get("attributes").obj() ?: return@mapNotNull null
            val file = (attributes["files"] as? JsonArray)?.firstOrNull().obj() ?: return@mapNotNull null
            val fileId = file.int("file_id") ?: return@mapNotNull null
            val language = attributes.string("language").orEmpty()
            OnlineSubtitle(
                service = service,
                reference = fileId.toString(),
                language = SubtitleLanguages.code(language) ?: language,
                release = attributes.string("release")?.takeIf { it.isNotEmpty() } ?: file.string("file_name").orEmpty(),
                downloads = attributes.int("download_count"),
                hearingImpaired = attributes.bool("hearing_impaired") ?: false,
            )
        }
    }

    override suspend fun download(subtitle: OnlineSubtitle, query: SubtitleQuery): DownloadedSubtitle = lock.withLock {
        if (session == null && username != null && password != null && !loginRefused) logIn()
        val link = try {
            requestLink(subtitle.reference)
        } catch (e: HttpException.BadStatus) {
            if (e.code != 401 || session == null) throw SubtitleHttp.translate(e, service)
            // An expired session: sign in once more.
            session = null
            logIn()
            SubtitleHttp.translating(service) { requestLink(subtitle.reference) }
        }
        val data = SubtitleHttp.translating(service) { http.getLimited(link.first, mapOf("User-Agent" to userAgent), SubtitleArchive.MAX_SUBTITLE_DOWNLOAD) }
        DownloadedSubtitle(link.second ?: "subtitle.srt", data)
    }

    /** The file's address and name. */
    private suspend fun requestLink(fileId: String): Pair<String, String?> {
        val id = fileId.toIntOrNull() ?: throw SubtitleSearchException.ServiceError(service, "unknown file")
        val body = buildJsonObject { put("file_id", id) }.toString().toByteArray()
        val answer = SubtitleHttp.parse(service, http.post(url("/download"), headers(json = true), body)).obj()
        // Over HTTPS only: the link is the service's answer, the file goes to the player.
        val link = answer.string("link")?.takeIf { it.startsWith("https://") }
            ?: throw SubtitleSearchException.ServiceError(service, "invalid download link")
        return link to answer.string("file_name")
    }

    private suspend fun logIn() {
        val user = username ?: return
        val secret = password ?: return
        val body = buildJsonObject {
            put("username", JsonPrimitive(user))
            put("password", JsonPrimitive(secret))
        }.toString().toByteArray()
        val headers = mapOf("Api-Key" to apiKey, "User-Agent" to userAgent, "Accept" to "application/json", "Content-Type" to "application/json")
        val data = try {
            http.post("https://$HOST/api/v1/login", headers, body)
        } catch (e: HttpException.BadStatus) {
            // 401 for a wrong password; 400 or 403 for a login the service will not take either.
            if (e.code == 400 || e.code == 401 || e.code == 403) {
                loginRefused = true
                throw SubtitleSearchException.InvalidCredentials(service)
            }
            throw SubtitleHttp.translate(e, service)
        }
        val login = SubtitleHttp.parse(service, data).obj()
        val token = login.string("token") ?: throw SubtitleSearchException.ServiceError(service, "unreadable answer")
        // The host the account is served from: one of the service's own, else the usual one (the key and the session
        // token go to it).
        session = token to (login.string("base_url")?.takeIf(::isServiceHost) ?: HOST)
    }

    /**
     * Checks the API key with the service (its list of formats needs one) and, when an account is given, signs in with
     * it: what Settings saves only once accepted.
     */
    suspend fun checkCredentials(): CredentialCheck = try {
        http.get("https://$HOST/api/v1/infos/formats", headers())
        if (username != null && password != null) lock.withLock { logIn() }
        CredentialCheck.ACCEPTED
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        CredentialCheck.failure(e)
    }

    companion object {
        const val HOST = "api.opensubtitles.com"

        private val hostName = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*")

        /** "vip-api.opensubtitles.com": a host name of the service's; not "x@evil.example", nor "evil.example". */
        fun isServiceHost(host: String): Boolean {
            val lower = host.lowercase()
            return hostName.matches(lower) && (lower == "opensubtitles.com" || lower.endsWith(".opensubtitles.com"))
        }
    }
}
