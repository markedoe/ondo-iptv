package com.markedoe.ondo.core.playback

import kotlinx.serialization.Serializable

/**
 * A sound or subtitle track of a stream as the track memory compares them: its language, its own label, whether it is
 * forced (foreign dialogue only) or for the deaf and hard of hearing, and its codec. Never its place in the stream:
 * two episodes list their tracks in different orders.
 */
data class StreamTrack(
    val language: String?,
    val label: String? = null,
    val forced: Boolean = false,
    val hearingImpaired: Boolean = false,
    val codec: String? = null,
)

/** What a choice in the player keeps of the track chosen (docs/TRACKS.md): what [TrackMemory.match] finds it by. */
@Serializable
data class TrackPick(
    val language: String? = null,
    val label: String? = null,
    val forced: Boolean = false,
    val hearingImpaired: Boolean = false,
    val codec: String? = null,
) {
    companion object {
        fun of(track: StreamTrack) = TrackPick(
            TrackMemory.language(track.language),
            track.label?.trim()?.takeIf { it.isNotEmpty() },
            track.forced,
            track.hearingImpaired,
            track.codec,
        )
    }
}

/**
 * The tracks the viewer chose for a film or a series ([key]: its playlist, kind and id, as its progress has them; a
 * series' for all its episodes). Each part only once chosen: [audio] null leaves the sound to the profile's default;
 * subtitles are a track ([subtitles]), off ([subtitlesOff]), or not chosen (neither).
 */
@Serializable
data class TitleTracks(
    val key: String,
    val audio: TrackPick? = null,
    val subtitles: TrackPick? = null,
    val subtitlesOff: Boolean = false,
    /** When it was last chosen (epoch milliseconds). */
    val at: Long = 0,
)

/**
 * A profile's languages for a title nothing was chosen for yet (Settings, Player): [audio] null is automatic (the
 * device's languages, then the stream's default track); [subtitles] null is off.
 */
@Serializable
data class TrackDefaults(val audio: String? = null, val subtitles: String? = null)

/** The tracks a stream starts with: indexes in the lists given to [TrackMemory.plan]; audio null keeps the player's, subtitles null is off. */
data class TrackPlan(val audio: Int?, val subtitles: Int?)

/**
 * The tracks kept per film and per series, and a profile's default languages (docs/TRACKS.md): the same rules and
 * cases as the Swift `TrackMemory` (`fixtures/track-memory`).
 */
object TrackMemory {
    /** Titles remembered at most per profile, the latest first. */
    const val MAX_TITLES = 500

    /** The language offered in Settings for a profile's defaults, as codes; the names come from the system. */
    val CHOICES = listOf("en", "fr", "es", "pt", "de", "it", "nl", "ar", "hi", "zh", "ja", "ko", "ru", "tr", "pl")

    /**
     * A language code as the memory compares them: the language alone, in two letters when it has two ("fre", "fra",
     * "fr-CA" and "FR" are "fr"); null for none or an undetermined one ("und", "mul", "zxx", "mis").
     */
    fun language(code: String?): String? {
        val primary = code?.trim()?.split('-', '_')?.firstOrNull()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (primary in UNDETERMINED) return null
        return THREE_LETTERS[primary] ?: primary
    }

    /**
     * The track of [tracks] that [pick] stands for: one in its language (by its label when it named none), the most
     * like it (forced, hearing impaired, label, codec); [current] when it is as like it as any. Null when none has
     * its language.
     */
    fun match(tracks: List<StreamTrack>, pick: TrackPick, current: Int? = null): Int? {
        val wanted = language(pick.language)
        val label = pick.label?.trim()?.takeIf { it.isNotEmpty() }
        val candidates = tracks.indices.filter { index ->
            val track = tracks[index]
            if (wanted != null) language(track.language) == wanted
            else label != null && track.label?.trim().equals(label, ignoreCase = true)
        }
        if (candidates.isEmpty()) return null
        fun score(index: Int): Int {
            val track = tracks[index]
            var score = 0
            if (track.forced == pick.forced) score += 8
            if (track.hearingImpaired == pick.hearingImpaired) score += 4
            if (label != null && track.label?.trim().equals(label, ignoreCase = true)) score += 2
            if (pick.codec != null && track.codec == pick.codec) score += 1
            return score
        }
        val best = candidates.maxOf(::score)
        if (current != null && current in candidates && score(current) == best) return current
        return candidates.first { score(it) == best }
    }

    /**
     * The tracks a stream starts with: the title's [memory] first, then the profile's [defaults]; a remembered track
     * the stream lacks falls back to the default, and a default it lacks to the stream's own (audio) or to none
     * (subtitles). Subtitles turned off for the title stay off.
     */
    fun plan(
        audio: List<StreamTrack>,
        subtitles: List<StreamTrack>,
        memory: TitleTracks?,
        defaults: TrackDefaults?,
        currentAudio: Int? = null,
        currentSubtitles: Int? = null,
    ): TrackPlan {
        val sound = memory?.audio?.let { match(audio, it, currentAudio) }
            ?: defaults?.audio?.let { match(audio, TrackPick(language = it), currentAudio) }
        val text = if (memory?.subtitlesOff == true) null
        else memory?.subtitles?.let { match(subtitles, it, currentSubtitles) }
            ?: defaults?.subtitles?.let { match(subtitles, TrackPick(language = it), currentSubtitles) }
        return TrackPlan(sound, text)
    }

    /** The sound languages to ask a player for before it lists the tracks, the first preferred. */
    fun audioLanguages(memory: TitleTracks?, defaults: TrackDefaults?): List<String> =
        listOfNotNull(language(memory?.audio?.language), language(defaults?.audio)).distinct()

    /** The subtitle languages to ask a player for before it lists the tracks, the first preferred; empty: none. */
    fun subtitleLanguages(memory: TitleTracks?, defaults: TrackDefaults?): List<String> =
        if (memory?.subtitlesOff == true) emptyList()
        else listOfNotNull(language(memory?.subtitles?.language), language(defaults?.subtitles)).distinct()

    /** What is remembered for a title known by [keys] (a film's versions: the one playing first), the latest choice. */
    fun remembered(memories: List<TitleTracks>, keys: List<String>): TitleTracks? =
        if (keys.isEmpty()) null else memories.firstOrNull { it.key in keys }

    /**
     * [memories] with a choice made while [keys]`.first()` plays: [audio] a sound track chosen, [subtitles] a subtitle
     * track chosen, [subtitlesOff] subtitles turned off. What the choice does not say stays as remembered for the title
     * (another version's included); the title comes first, and only the latest [MAX_TITLES] are kept.
     */
    fun remember(
        memories: List<TitleTracks>,
        keys: List<String>,
        audio: TrackPick? = null,
        subtitles: TrackPick? = null,
        subtitlesOff: Boolean = false,
        at: Long,
    ): List<TitleTracks> {
        val key = keys.firstOrNull() ?: return memories
        if (audio == null && subtitles == null && !subtitlesOff) return memories
        val base = remembered(memories, keys)?.copy(key = key) ?: TitleTracks(key)
        val chosen = base.copy(
            audio = audio ?: base.audio,
            subtitles = if (subtitlesOff) null else subtitles ?: base.subtitles,
            subtitlesOff = if (subtitles != null) false else subtitlesOff || base.subtitlesOff,
            at = at,
        )
        return (listOf(chosen) + memories.filter { it.key != key }).take(MAX_TITLES)
    }

    /**
     * The key a title's tracks are kept under, from its progress key (`playlist|KIND|id|episode`): the film's, or the
     * series' for every episode; null for a channel or anything else.
     */
    fun titleKey(progressKey: String?, kinds: Set<String> = setOf("MOVIE", "SERIES")): String? {
        val parts = progressKey?.split('|') ?: return null
        if (parts.size < 3 || parts[1] !in kinds || parts[2].isEmpty()) return null
        return parts.take(3).joinToString("|")
    }

    private val UNDETERMINED = setOf("und", "mul", "zxx", "mis", "qaa")

    /** ISO 639-2 codes (bibliographic and terminology) of languages that have two letters, and older two-letter codes. */
    private val THREE_LETTERS = mapOf(
        "eng" to "en", "fre" to "fr", "fra" to "fr", "spa" to "es", "por" to "pt", "ger" to "de", "deu" to "de",
        "ita" to "it", "dut" to "nl", "nld" to "nl", "ara" to "ar", "hin" to "hi", "chi" to "zh", "zho" to "zh",
        "jpn" to "ja", "kor" to "ko", "rus" to "ru", "tur" to "tr", "pol" to "pl", "swe" to "sv", "dan" to "da",
        "nor" to "no", "nob" to "no", "nno" to "no", "nb" to "no", "nn" to "no", "fin" to "fi", "gre" to "el",
        "ell" to "el", "heb" to "he", "iw" to "he", "hun" to "hu", "cze" to "cs", "ces" to "cs", "rum" to "ro",
        "ron" to "ro", "ukr" to "uk", "vie" to "vi", "tha" to "th", "ind" to "id", "in" to "id", "per" to "fa",
        "fas" to "fa", "urd" to "ur", "ben" to "bn", "tam" to "ta", "tel" to "te", "may" to "ms", "msa" to "ms",
        "bul" to "bg", "hrv" to "hr", "srp" to "sr", "slo" to "sk", "slk" to "sk", "slv" to "sl", "cat" to "ca",
        "alb" to "sq", "sqi" to "sq", "mac" to "mk", "mkd" to "mk", "lit" to "lt", "lav" to "lv", "est" to "et",
        "ice" to "is", "isl" to "is", "kur" to "ku", "pan" to "pa", "mar" to "mr", "guj" to "gu", "kan" to "kn",
        "mal" to "ml", "tgl" to "tl", "fil" to "tl",
    )
}
