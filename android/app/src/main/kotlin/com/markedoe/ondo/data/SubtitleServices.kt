package com.markedoe.ondo.data

import android.content.SharedPreferences
import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.subtitles.Addic7edClient
import com.markedoe.ondo.core.subtitles.CredentialCheck
import com.markedoe.ondo.core.subtitles.OnlineSubtitle
import com.markedoe.ondo.core.subtitles.OpenSubtitlesClient
import com.markedoe.ondo.core.subtitles.SubDLClient
import com.markedoe.ondo.core.subtitles.SubSourceClient
import com.markedoe.ondo.core.subtitles.SubtitleProvider
import com.markedoe.ondo.core.subtitles.SubtitleQuery
import com.markedoe.ondo.core.subtitles.SubtitleSearch
import com.markedoe.ondo.core.subtitles.SubtitleService
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A field of a subtitle service's account, named as the service names it (the Apple apps' `SubtitleCredential`). */
enum class SubtitleCredential(val service: SubtitleService, val key: String) {
    OPEN_SUBTITLES_KEY(SubtitleService.OPEN_SUBTITLES, "opensubtitles-key"),
    OPEN_SUBTITLES_USERNAME(SubtitleService.OPEN_SUBTITLES, "opensubtitles-username"),
    OPEN_SUBTITLES_PASSWORD(SubtitleService.OPEN_SUBTITLES, "opensubtitles-password"),
    SUBDL_KEY(SubtitleService.SUBDL, "subdl-key"),
    SUBSOURCE_KEY(SubtitleService.SUBSOURCE, "subsource-key"),
    ;

    companion object {
        /** The fields of [service], its key first; none for Addic7ed (no account). */
        fun fields(service: SubtitleService): List<SubtitleCredential> = entries.filter { it.service == service }
    }
}

/** What Settings shows of the subtitle services: those with a key saved, and whether Addic7ed is searched. */
data class SubtitleServicesState(val saved: Set<SubtitleService> = emptySet(), val addic7ed: Boolean = false) {
    /** Whether the player can search at all. */
    val any: Boolean get() = saved.isNotEmpty() || addic7ed
}

/** A subtitle downloaded for a title, kept on this device: resuming the title shows it again. */
@Serializable
data class ChosenSubtitle(
    /** The file in the app's private folder. */
    val path: String,
    /** ISO 639-1 code. */
    val language: String,
    /** "srt", "vtt", "ass" or "ssa". */
    val format: String,
    /** The service it came from, shown as the track's label. */
    val label: String,
)

/**
 * The viewer's own keys and accounts for the subtitle services searched from the player, as on the Apple apps:
 * OpenSubtitles.com (API key, and an optional account), SubDL and SubSource (API key), and Addic7ed (no account, off
 * until turned on). Each key is checked with its service when it is saved, and kept only once the service accepts it.
 *
 * They stay on this device, in a private file of the app's (not in the settings sync carries, as the Apple apps keep
 * them in the keychain), and are never written to a log. The subtitles downloaded are kept here too, per title.
 */
class SubtitleServices(
    private val prefs: SharedPreferences,
    private val http: HttpClient,
    /** How the app introduces itself to the services (OpenSubtitles requires a name and version). */
    private val userAgent: String,
    /** Where downloaded subtitles are kept. */
    private val folder: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(readState())
    val state: StateFlow<SubtitleServicesState> = _state.asStateFlow()

    private var search: SubtitleSearch? = null

    private fun readState() = SubtitleServicesState(
        saved = SubtitleService.entries.filter { service -> SubtitleCredential.fields(service).firstOrNull()?.let { credential(it) } != null }.toSet(),
        addic7ed = prefs.getBoolean(ADDIC7ED, false),
    )

    fun credential(field: SubtitleCredential): String? = prefs.getString(field.key, null)?.takeIf { it.isNotEmpty() }

    private fun clean(field: SubtitleCredential, value: String?): String? =
        (if (field == SubtitleCredential.OPEN_SUBTITLES_PASSWORD) value else value?.trim())?.takeIf { it.isNotEmpty() }

    /**
     * Checks [service]'s key (and OpenSubtitles' account) with the service, and saves them only once it accepts them.
     * An empty key removes what was saved, without a check: null then.
     */
    suspend fun checkAndSave(service: SubtitleService, values: Map<SubtitleCredential, String>): CredentialCheck? {
        val fields = SubtitleCredential.fields(service)
        val typed = fields.associateWith { clean(it, values[it]) }
        val key = typed[fields.firstOrNull() ?: return null]
        if (key == null) {
            remove(service)
            return null
        }
        val check = withContext(io) {
            when (service) {
                SubtitleService.OPEN_SUBTITLES -> OpenSubtitlesClient(
                    key, typed[SubtitleCredential.OPEN_SUBTITLES_USERNAME], typed[SubtitleCredential.OPEN_SUBTITLES_PASSWORD], userAgent, http,
                ).checkCredentials()
                SubtitleService.SUBDL -> SubDLClient(key, http).checkKey()
                SubtitleService.SUBSOURCE -> SubSourceClient(key, http).checkKey()
                SubtitleService.ADDIC7ED -> CredentialCheck.ACCEPTED
            }
        }
        if (check == CredentialCheck.ACCEPTED) save(typed)
        return check
    }

    /** Removes [service]'s key and account. */
    fun remove(service: SubtitleService) = save(SubtitleCredential.fields(service).associateWith { null })

    fun setAddic7ed(on: Boolean) {
        prefs.edit().putBoolean(ADDIC7ED, on).apply()
        changed()
    }

    private fun save(values: Map<SubtitleCredential, String?>) {
        val editor = prefs.edit()
        for ((field, value) in values) if (value == null) editor.remove(field.key) else editor.putString(field.key, value)
        editor.apply()
        changed()
    }

    /** New credentials, new clients (a refused login may be tried again), and new searches. */
    private fun changed() {
        search = null
        _state.value = readState()
    }

    /** The search over the services configured; kept (with its answers) until the credentials change. */
    fun search(): SubtitleSearch = search ?: SubtitleSearch(providers()).also { search = it }

    private fun providers(): List<SubtitleProvider> = buildList {
        credential(SubtitleCredential.OPEN_SUBTITLES_KEY)?.let {
            add(OpenSubtitlesClient(it, credential(SubtitleCredential.OPEN_SUBTITLES_USERNAME), credential(SubtitleCredential.OPEN_SUBTITLES_PASSWORD), userAgent, http))
        }
        credential(SubtitleCredential.SUBDL_KEY)?.let { add(SubDLClient(it, http)) }
        credential(SubtitleCredential.SUBSOURCE_KEY)?.let { add(SubSourceClient(it, http)) }
        if (prefs.getBoolean(ADDIC7ED, false)) add(Addic7edClient(userAgent, http))
    }

    /**
     * Downloads [subtitle] and keeps it for [titleKey] (the history's key of the film or episode): the next time the
     * title plays, the player loads it again.
     */
    suspend fun download(subtitle: OnlineSubtitle, query: SubtitleQuery, titleKey: String?): ChosenSubtitle {
        val file = search().download(subtitle, query)
        return withContext(io) {
            folder.mkdirs()
            val name = digest(titleKey ?: subtitle.id) + "." + file.format
            // An earlier one of this title, in another format, goes.
            folder.listFiles { f -> f.nameWithoutExtension == name.substringBeforeLast('.') }?.forEach { it.delete() }
            val target = File(folder, name)
            target.writeBytes(file.data)
            val chosen = ChosenSubtitle(target.absolutePath, subtitle.language, file.format, subtitle.service.displayName)
            if (titleKey != null) {
                prefs.edit().putString(CHOSEN + titleKey, Json.encodeToString(ChosenSubtitle.serializer(), chosen)).apply()
                trim()
            }
            chosen
        }
    }

    /** The subtitle downloaded for [titleKey], when its file is still there. */
    fun chosen(titleKey: String?): ChosenSubtitle? {
        val text = titleKey?.let { prefs.getString(CHOSEN + it, null) } ?: return null
        return runCatching { Json.decodeFromString(ChosenSubtitle.serializer(), text) }.getOrNull()?.takeIf { File(it.path).isFile }
    }

    /** Keeps the [MAX_KEPT] latest subtitles. */
    private fun trim() {
        val files = folder.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        if (files.size <= MAX_KEPT) return
        val gone = files.drop(MAX_KEPT).map { it.absolutePath }.toSet()
        files.drop(MAX_KEPT).forEach { it.delete() }
        val editor = prefs.edit()
        prefs.all.filterKeys { it.startsWith(CHOSEN) }.forEach { (key, value) ->
            val path = runCatching { Json.decodeFromString(ChosenSubtitle.serializer(), value as String).path }.getOrNull()
            if (path == null || path in gone) editor.remove(key)
        }
        editor.apply()
    }

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    companion object {
        /** The name of the private preferences file. */
        const val FILE = "subtitle_services"
        const val MAX_KEPT = 100
        private const val ADDIC7ED = "addic7ed"
        private const val CHOSEN = "chosen:"
    }
}
