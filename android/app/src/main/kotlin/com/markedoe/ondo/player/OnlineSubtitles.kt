package com.markedoe.ondo.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.markedoe.ondo.R
import com.markedoe.ondo.core.subtitles.OnlineSubtitle
import com.markedoe.ondo.core.subtitles.SubtitleSearchException
import com.markedoe.ondo.core.subtitles.SubtitleService
import com.markedoe.ondo.data.ChosenSubtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What the online search of the Subtitles panel needs: the services, and what to do with a subtitle downloaded. */
interface OnlineSubtitleSource {
    /** Whether a service is set up in Settings (a key saved, or Addic7ed turned on). */
    val configured: Boolean

    suspend fun search(hint: SubtitleSearchHint, languages: List<String>): com.markedoe.ondo.core.subtitles.SubtitleSearch.Outcome

    suspend fun download(subtitle: OnlineSubtitle, hint: SubtitleSearchHint, languages: List<String>): ChosenSubtitle
}

/**
 * The Subtitles panel's "Search online…": the services set up in Settings searched for the film or episode playing,
 * in the viewer's subtitle languages; a result chosen is downloaded and shown ([onLoaded]), and kept for the title.
 * Runs on [scope] (the player's, on the main thread); the panels show [phase], [results] and [failures].
 */
@Stable
class OnlineSubtitles(
    private val scope: CoroutineScope,
    private val source: OnlineSubtitleSource,
    val hint: SubtitleSearchHint,
    languages: List<String>,
    private val onLoaded: (ChosenSubtitle) -> Unit,
) {
    enum class Phase { IDLE, SEARCHING, RESULTS, DOWNLOADING, FAILED }

    var languages by mutableStateOf(languages)
        private set
    var phase by mutableStateOf(Phase.IDLE)
        private set
    var results by mutableStateOf<List<OnlineSubtitle>>(emptyList())
        private set

    /** Each service that failed, with why (in the app's words: [message]). */
    var failures by mutableStateOf<Map<SubtitleService, Throwable>>(emptyMap())
        private set

    /** Why the download of the subtitle chosen failed. */
    var downloadError by mutableStateOf<Throwable?>(null)
        private set

    /** The panel shows the languages to choose from instead of the results. */
    var choosingLanguage by mutableStateOf(false)

    val configured: Boolean get() = source.configured

    private var job: Job? = null

    /** Searches again unless the answers for these languages are already there. */
    fun start() {
        if (!configured || phase == Phase.SEARCHING || phase == Phase.RESULTS || phase == Phase.DOWNLOADING) return
        search()
    }

    private fun search() {
        job?.cancel()
        phase = Phase.SEARCHING
        downloadError = null
        job = scope.launch {
            try {
                val outcome = source.search(hint, languages)
                results = outcome.results
                failures = outcome.failures
                phase = Phase.RESULTS
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                results = emptyList()
                failures = emptyMap()
                downloadError = e
                phase = Phase.FAILED
            }
        }
    }

    /**
     * Stops a search or a download under way (another title plays): a subtitle that arrives after is not loaded.
     */
    fun cancel() {
        job?.cancel()
        job = null
        if (phase == Phase.SEARCHING || phase == Phase.DOWNLOADING) phase = Phase.IDLE
    }

    /** Searches in [language] first (the second language kept after it). */
    fun chooseLanguage(language: String) {
        choosingLanguage = false
        languages = (listOf(language) + languages).distinct().take(2)
        search()
    }

    fun choose(subtitle: OnlineSubtitle) {
        if (phase == Phase.DOWNLOADING) return
        job?.cancel()
        phase = Phase.DOWNLOADING
        downloadError = null
        job = scope.launch {
            try {
                val chosen = source.download(subtitle, hint, languages)
                phase = Phase.RESULTS
                onLoaded(chosen)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                downloadError = e
                phase = Phase.RESULTS
            }
        }
    }

    companion object {
        /** What to tell the viewer of a service's failure (string resource and its argument, the service's name). */
        fun message(error: Throwable, service: SubtitleService?): Pair<Int, String?> = when (error) {
            is SubtitleSearchException.InvalidCredentials -> R.string.online_subtitles_refused to error.service.displayName
            is SubtitleSearchException.QuotaReached -> R.string.online_subtitles_quota to error.service.displayName
            is SubtitleSearchException.NoSubtitleInArchive -> R.string.online_subtitles_no_file to null
            else -> if (service != null) R.string.online_subtitles_unreachable to service.displayName else R.string.online_subtitles_download_failed to null
        }
    }
}

/** "Copper Valley · S03E03", "Northern Lights (2025)": what is searched. */
fun SubtitleSearchHint.label(): String = when {
    season != null && episode != null -> "$title · " + "S%02dE%02d".format(java.util.Locale.ROOT, season, episode)
    year != null -> "$title ($year)"
    else -> title
}

/** A language of the search in the app's words; its code when Java does not know it. */
fun onlineLanguage(code: String, locale: java.util.Locale): String = TrackChoices.languageName(code, locale) ?: code

/** The languages a search can be in, by their names in the app's language. */
fun onlineLanguageCodes(locale: java.util.Locale): List<String> = com.markedoe.ondo.core.subtitles.SubtitleLanguages.codes.sortedBy { onlineLanguage(it, locale) }

/** Under a result: its release, its service, and whether it is for the deaf and hard of hearing. */
fun onlineDetail(subtitle: OnlineSubtitle, words: TrackWords): String =
    listOfNotNull(subtitle.release.takeIf { it.isNotBlank() }, subtitle.service.displayName, words.hearingImpaired.takeIf { subtitle.hearingImpaired }).joinToString(" · ")

/** The lines above the results (searching, nothing found, a service that failed…), each with its test tag. */
fun onlineStatus(online: OnlineSubtitles): List<Pair<String, @Composable () -> String>> = buildList {
    when (online.phase) {
        OnlineSubtitles.Phase.SEARCHING -> add("onlineSearching" to @Composable { stringResource(R.string.online_subtitles_searching) })
        OnlineSubtitles.Phase.DOWNLOADING -> add("onlineDownloading" to @Composable { stringResource(R.string.online_subtitles_downloading) })
        OnlineSubtitles.Phase.RESULTS -> if (online.results.isEmpty()) add("onlineNone" to @Composable { stringResource(R.string.online_subtitles_none) })
        else -> Unit
    }
    online.downloadError?.let { error -> add("onlineError" to @Composable { onlineMessage(error, null) }) }
    online.failures.forEach { (service, error) -> add("onlineFailure-${service.name}" to @Composable { onlineMessage(error, service) }) }
}

@Composable
private fun onlineMessage(error: Throwable, service: SubtitleService?): String {
    val (text, name) = OnlineSubtitles.message(error, service)
    return if (name != null) stringResource(text, name) else stringResource(text)
}
