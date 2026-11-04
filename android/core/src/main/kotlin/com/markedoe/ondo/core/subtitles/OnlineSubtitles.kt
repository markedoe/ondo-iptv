package com.markedoe.ondo.core.subtitles

import com.markedoe.ondo.core.net.HttpException
import com.markedoe.ondo.core.xtream.lenientBool
import com.markedoe.ondo.core.xtream.lenientInt
import com.markedoe.ondo.core.xtream.lenientString
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.zip.ZipInputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** What is being watched, to find its subtitles (as on the Apple apps, `SubtitleQuery`). */
data class SubtitleQuery(
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** ISO 639-1 codes, preferred first. */
    val languages: List<String>,
    /** TMDb's id of the film, or of the series for an episode, when the provider gives it: a search by id, not by name. */
    val tmdbId: Int? = null,
) {
    val isEpisode: Boolean get() = season != null && episode != null

    /** "S01E02", how releases name an episode. */
    val episodeCode: String? get() = if (season != null && episode != null) "S%02dE%02d".format(java.util.Locale.ROOT, season, episode) else null
}

/** The subtitle services Ondo can search, with the viewer's own key or account. */
enum class SubtitleService(val displayName: String) {
    OPEN_SUBTITLES("OpenSubtitles"),
    SUBDL("SubDL"),
    SUBSOURCE("SubSource"),
    ADDIC7ED("Addic7ed"),
}

/** One subtitle found online. */
data class OnlineSubtitle(
    val service: SubtitleService,
    /** What the service needs to download it (file id, path or address). */
    val reference: String,
    /** ISO 639-1 code when known, else the service's own name for the language. */
    val language: String,
    /** The release the subtitle was timed for, or its file name. */
    val release: String,
    val downloads: Int? = null,
    val hearingImpaired: Boolean = false,
) {
    val id: String get() = "${service.name}:$reference"
}

/** A downloaded subtitle file: SRT, WebVTT or ASS, by its name's extension. */
class DownloadedSubtitle(val fileName: String, val data: ByteArray) {
    /** "srt", "vtt", "ass" or "ssa"; "srt" for a name without a known one (the services' plain files are SRT). */
    val format: String get() = fileName.substringAfterLast('.', "").lowercase().takeIf { it in FORMATS } ?: "srt"

    companion object {
        val FORMATS = setOf("srt", "vtt", "ass", "ssa")
    }
}

/** Why a service gave nothing: what the viewer can act on. */
sealed class SubtitleSearchException(message: String) : IOException(message) {
    /** The service refused the key or the login. */
    class InvalidCredentials(val service: SubtitleService) : SubtitleSearchException("${service.displayName} refused the key or the login")

    /** The service's limit of searches or downloads is reached for now. */
    class QuotaReached(val service: SubtitleService) : SubtitleSearchException("${service.displayName}: limit reached")

    /** The file or archive the service sent is larger than any subtitle (or than what the app reads of an archive). */
    class TooLarge : SubtitleSearchException("The subtitle file is too large")

    /** The archive the service sent holds no subtitle file Ondo can show. */
    class NoSubtitleInArchive : SubtitleSearchException("The archive holds no SRT, WebVTT or ASS subtitle")

    class ServiceError(val service: SubtitleService, detail: String) : SubtitleSearchException("${service.displayName}: $detail")
}

/** What a service said about a key or a login, checked when the viewer saves it: only an accepted one is saved. */
enum class CredentialCheck {
    ACCEPTED,
    REFUSED,

    /** The service could not be reached, or did not say (an error of its own): nothing is saved. */
    UNREACHABLE,

    /** The service's limit of requests is reached for now: it said nothing of the key, nothing is saved. */
    LIMITED,

    /** What was typed is not enough to check (a username without its password, or the reverse): nothing is saved. */
    INCOMPLETE,
    ;

    companion object {
        /** What the failure of a request made with the credentials says: refused on 401 or 403, limited on 429. */
        fun failure(error: Throwable): CredentialCheck = when {
            error is SubtitleSearchException.InvalidCredentials -> REFUSED
            error is SubtitleSearchException.QuotaReached -> LIMITED
            error is HttpException.BadStatus && (error.code == 401 || error.code == 403) -> REFUSED
            error is HttpException.BadStatus && error.code == 429 -> LIMITED
            else -> UNREACHABLE
        }
    }
}

interface SubtitleProvider {
    val service: SubtitleService
    suspend fun search(query: SubtitleQuery): List<OnlineSubtitle>
    suspend fun download(subtitle: OnlineSubtitle, query: SubtitleQuery): DownloadedSubtitle
}

/**
 * Searches every configured service at once. A service that fails does not hide the others' results: its error is
 * reported next to them. The answers of a search are kept ([CACHE_SIZE] searches, for [CACHE_MS]): opening the
 * search again for the same title asks no service again (the services count requests).
 */
class SubtitleSearch(
    val providers: List<SubtitleProvider>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    class Outcome(val results: List<OnlineSubtitle>, val failures: Map<SubtitleService, Throwable>)

    private val cache = LinkedHashMap<SubtitleQuery, Pair<Long, Outcome>>()
    private val cacheLock = Mutex()

    suspend fun search(query: SubtitleQuery): Outcome {
        cacheLock.withLock {
            cache[query]?.let { (at, outcome) -> if (now() - at < CACHE_MS) return outcome else cache.remove(query) }
        }
        val found = coroutineScope {
            providers.map { provider -> provider.service to async { runCatching { provider.search(query) } } }.map { (service, answer) -> service to answer.await() }
        }
        val results = found.flatMap { (_, answer) -> answer.getOrNull().orEmpty() }
        val failures = found.mapNotNull { (service, answer) -> answer.exceptionOrNull()?.let { service to it } }.toMap()
        found.forEach { (_, answer) -> (answer.exceptionOrNull() as? kotlinx.coroutines.CancellationException)?.let { throw it } }
        val outcome = Outcome(ranked(results, query.languages, providers.map { it.service }), failures)
        // A failed service is asked again next time.
        if (failures.isEmpty()) cacheLock.withLock {
            cache[query] = now() to outcome
            while (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
        }
        return outcome
    }

    suspend fun download(subtitle: OnlineSubtitle, query: SubtitleQuery): DownloadedSubtitle {
        val provider = providers.firstOrNull { it.service == subtitle.service } ?: throw SubtitleSearchException.ServiceError(subtitle.service, "not configured")
        return provider.download(subtitle, query)
    }

    companion object {
        const val CACHE_SIZE = 50
        const val CACHE_MS = 60 * 60 * 1000L

        /** Preferred language first, then the services in their order, then the most downloaded. */
        fun ranked(results: List<OnlineSubtitle>, languages: List<String>, services: List<SubtitleService>): List<OnlineSubtitle> {
            fun languageRank(subtitle: OnlineSubtitle) = languages.indexOf(subtitle.language).let { if (it < 0) languages.size else it }
            fun serviceRank(subtitle: OnlineSubtitle) = services.indexOf(subtitle.service).let { if (it < 0) services.size else it }
            return results.sortedWith(compareBy<OnlineSubtitle>({ languageRank(it) }, { serviceRank(it) }, { -(it.downloads ?: 0) }, { it.release }))
        }
    }
}

/** Language codes and the English names some services use instead. */
object SubtitleLanguages {
    private val englishNames = mapOf(
        "ar" to "Arabic", "bg" to "Bulgarian", "cs" to "Czech", "da" to "Danish", "de" to "German", "el" to "Greek", "en" to "English",
        "es" to "Spanish", "fa" to "Persian", "fi" to "Finnish", "fr" to "French", "he" to "Hebrew", "hi" to "Hindi", "hr" to "Croatian",
        "hu" to "Hungarian", "id" to "Indonesian", "it" to "Italian", "ja" to "Japanese", "ko" to "Korean", "nl" to "Dutch",
        "no" to "Norwegian", "pl" to "Polish", "pt" to "Portuguese", "ro" to "Romanian", "ru" to "Russian", "sr" to "Serbian",
        "sv" to "Swedish", "th" to "Thai", "tr" to "Turkish", "uk" to "Ukrainian", "vi" to "Vietnamese", "zh" to "Chinese",
    )

    val codes: List<String> get() = englishNames.keys.sorted()

    fun englishName(code: String): String? = englishNames[code.lowercase()]

    /** "french", "French", "FR", "fr", "pt-BR" or "pt_BR" -> "fr" / "pt"; null when unknown. */
    fun code(value: String): String? {
        val lowered = value.trim().lowercase()
        val base = lowered.split('-', '_').first()
        if (base in englishNames) return base
        // Three-letter codes (ISO 639-2) as Media3 and some services give them.
        java.util.Locale.getAvailableLocales().firstOrNull { runCatching { it.isO3Language }.getOrNull() == lowered }?.language?.takeIf { it in englishNames }?.let { return it }
        return englishNames.entries.firstOrNull { it.value.lowercase() == lowered }?.key
    }

    /** The languages a search starts with: the viewer's subtitle language, then the device's, then English; two at most. */
    fun defaults(preferred: String?, device: List<String>): List<String> =
        (listOfNotNull(preferred) + device + "en").mapNotNull(::code).distinct().take(2)
}

/**
 * The subtitle file to keep from an archive: the episode's when the archive holds a season, else the first SRT, else
 * the first WebVTT, else the first ASS. Read as it streams, keeping only the best file so far, within limits an
 * archive of subtitles never reaches ([MAX_ENTRIES] files, [MAX_FILE_BYTES] each, [MAX_TOTAL_BYTES] in all): a
 * hostile archive that inflates to gigabytes stops there instead of filling the memory.
 */
object SubtitleArchive {
    const val MAX_FILE_BYTES = 8 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 32L * 1024 * 1024
    const val MAX_ENTRIES = 200

    /** Largest archive downloaded (compressed), and largest plain subtitle file. */
    const val MAX_ARCHIVE_DOWNLOAD = 16L * 1024 * 1024
    const val MAX_SUBTITLE_DOWNLOAD = 5L * 1024 * 1024

    fun pick(data: ByteArray, episodeCode: String?): DownloadedSubtitle {
        var best: DownloadedSubtitle? = null
        var bestRank = Int.MAX_VALUE
        var entries = 0
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        try {
            ZipInputStream(ByteArrayInputStream(data)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > MAX_ENTRIES) throw SubtitleSearchException.TooLarge()
                    val name = entry.name.substringAfterLast('/')
                    val wanted = !entry.isDirectory && name.substringAfterLast('.', "").lowercase() in DownloadedSubtitle.FORMATS
                    val rank = if (wanted) rank(name, episodeCode) else Int.MAX_VALUE
                    // Only a better file than the one kept is read into memory; the others are counted as they pass.
                    val keep = wanted && (rank < bestRank || (rank == bestRank && name < best!!.fileName))
                    val bytes = if (keep) java.io.ByteArrayOutputStream() else null
                    var size = 0L
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        size += read
                        total += read
                        if (total > MAX_TOTAL_BYTES || (keep && size > MAX_FILE_BYTES)) throw SubtitleSearchException.TooLarge()
                        bytes?.write(buffer, 0, read)
                    }
                    if (bytes != null) {
                        best = DownloadedSubtitle(name, bytes.toByteArray())
                        bestRank = rank
                    }
                }
            }
        } catch (e: java.util.zip.ZipException) {
            throw SubtitleSearchException.NoSubtitleInArchive()
        }
        return best ?: throw SubtitleSearchException.NoSubtitleInArchive()
    }

    private fun rank(name: String, episodeCode: String?): Int {
        val matchesEpisode = episodeCode != null && name.uppercase().contains(episodeCode)
        val format = when (name.substringAfterLast('.', "").lowercase()) {
            "srt" -> 0
            "vtt" -> 1
            else -> 2
        }
        return (if (matchesEpisode) 0 else 3) + format
    }
}

/** What the services' HTTP failures mean for the viewer. */
internal object SubtitleHttp {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun translate(error: Throwable, service: SubtitleService): Throwable = when {
        error is HttpException.BadStatus && (error.code == 401 || error.code == 403) -> SubtitleSearchException.InvalidCredentials(service)
        error is HttpException.BadStatus && (error.code == 406 || error.code == 429) -> SubtitleSearchException.QuotaReached(service)
        error is HttpException.TooLarge -> SubtitleSearchException.TooLarge()
        else -> error
    }

    inline fun <T> translating(service: SubtitleService, block: () -> T): T = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        throw translate(e, service)
    }

    fun parse(service: SubtitleService, data: ByteArray): JsonElement = try {
        json.parseToJsonElement(data.decodeToString())
    } catch (e: Exception) {
        throw SubtitleSearchException.ServiceError(service, "unreadable answer")
    }

    /** `a=1&b=2`, values encoded, nulls left out, in the order given. */
    fun query(items: List<Pair<String, String?>>): String =
        items.mapNotNull { (name, value) -> value?.let { "$name=${URLEncoder.encode(it, "UTF-8").replace("+", "%20")}" } }.joinToString("&")
}

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonObject?.string(key: String): String? = this?.get(key).lenientString()
internal fun JsonObject?.int(key: String): Int? = this?.get(key).lenientInt()
internal fun JsonObject?.bool(key: String): Boolean? = this?.get(key).lenientBool()
