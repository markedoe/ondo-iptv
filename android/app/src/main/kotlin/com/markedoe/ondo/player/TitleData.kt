package com.markedoe.ondo.player

import androidx.annotation.StringRes
import com.markedoe.ondo.R
import com.markedoe.ondo.core.library.DefaultReason
import com.markedoe.ondo.core.library.FilmVersions
import com.markedoe.ondo.core.library.VersionWords
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.playback.MediaFormat
import com.markedoe.ondo.core.playback.Redaction
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.ui.UiMappers

/**
 * What the app read from the provider for the title played, shown under More, Stream Statistics (and held OK on a
 * version's chip on a film's TV page): the catalogue's raw names, categories and ids, the tags the version rules found,
 * what each version's chip says and which one plays first. For photographing a provider's names when a chip reads
 * wrong (docs/VERSIONS.md). Never an address, a login, a password or a server: every text goes through [Redaction].
 */
data class TitleData(
    /** The film's or the episode's lines. */
    val lines: List<Line>,
    /** The film's versions, in catalogue order (empty for an episode or a film alone). */
    val versions: List<Version> = emptyList(),
    /** Why the film's card plays the version marked [Version.first]; null for an episode or a film alone. */
    val reason: DefaultReason? = null,
) {
    /** One line: a field the app names in the viewer's language ([label]), else the provider's own field name ([key]). */
    data class Line(val key: String, val value: String, @StringRes val label: Int = 0)

    data class Version(
        /** What its chip says on the film's page; null for one in a hidden category (no chip). */
        val chip: String?,
        val name: String,
        /** "Films 4K (12)": the category's name and id. */
        val category: String,
        val id: String,
        /** "MKV", "MP4", "HLS"…; the provider's extension as given when the app does not know it. */
        val container: String?,
        /** The tags the version rules read ("UHD, DOLBY_VISION"); empty for none. */
        val tags: String,
        val playing: Boolean,
        /** The version the film's card plays and its page selects first. */
        val first: Boolean,
    )

    companion object {
        /** Versions the player's panel lists at most; the film's page lists them all. */
        const val PANEL_VERSIONS = 4

        /**
         * The data of the title [progressKey] names ("playlist|MOVIE|id|", "playlist|SERIES|id|episode"), shown as
         * [shownTitle]; null for a channel or a title the catalogue does not list. Off the main thread: it reads the
         * catalogue. [matchedTmdb]: the TMDb id the app matched the title to, if any.
         */
        fun of(
            state: OndoState,
            progressKey: String?,
            shownTitle: String,
            words: VersionWords = VersionWords(),
            matchedTmdb: Int? = null,
        ): TitleData? {
            val parts = progressKey?.split('|') ?: return null
            if (parts.size != 4) return null
            val secrets = secrets(state)
            fun clean(text: String) = Redaction.clean(text, secrets)
            return when (parts[1]) {
                ItemKind.MOVIE.name -> UiMappers.movie(state, parts[2])?.let { film(state, it, shownTitle, words, matchedTmdb, ::clean) }
                ItemKind.SERIES.name -> episode(state, parts[2], parts[3], shownTitle, matchedTmdb, ::clean)
                else -> null
            }
        }

        /** A film's data, [movie] the version played (or picked on its page). */
        fun film(
            state: OndoState,
            movie: Movie,
            shownTitle: String,
            words: VersionWords = VersionWords(),
            matchedTmdb: Int? = null,
            clean: (String) -> String = { Redaction.clean(it, secrets(state)) },
        ): TitleData {
            val categoryName = FilmVersions.categoryNames(state.content.movieCategories)
            val category = categoryName(movie.categoryId)
            val lines = listOfNotNull(
                Line("", clean(shownTitle), R.string.title_data_shown),
                Line("name", clean(movie.name)),
                Line("id", clean(movie.id)),
                movie.xtreamStreamId?.let { Line("stream_id", it.toString()) },
                Line("category", clean(categoryText(category, movie.categoryId))),
                movie.year?.let { Line("year", it.toString()) },
                Line("", tagText(movie.name, category), R.string.title_data_tags),
                Line("tmdb", tmdbText(movie.tmdbId?.toString(), state.movieDetails[movie.id]?.tmdbId, matchedTmdb, clean)),
                Line("container_extension", clean(containerText(movie))),
            )
            val all = UiMappers.films(state).versions(movie).distinctBy { it.id }
            if (all.size < 2) return TitleData(lines)
            val visible = UiMappers.visibleVersions(state, movie)
            val chips = if (visible.size > 1) FilmVersions.labels(visible, categoryName, words) else emptyList()
            val chipOf = visible.map { it.id }.zip(chips).toMap()
            val (first, reason) = UiMappers.defaultChoice(state, movie, visible)
            val versions = all.map { version ->
                val name = categoryName(version.categoryId)
                Version(
                    chip = chipOf[version.id]?.let(clean),
                    name = clean(version.name),
                    category = clean(categoryText(name, version.categoryId)),
                    id = clean(version.id),
                    container = containerText(version).let(clean).ifEmpty { null },
                    tags = tagText(version.name, name),
                    playing = version.id == movie.id,
                    first = version.id == first.id,
                )
            }
            return TitleData(lines, versions, reason)
        }

        private fun episode(
            state: OndoState,
            seriesId: String,
            episodeId: String,
            shownTitle: String,
            matchedTmdb: Int?,
            clean: (String) -> String,
        ): TitleData? {
            val series = UiMappers.series(state, seriesId) ?: return null
            val details = state.seriesDetails[seriesId]
            val episode = details?.seasons?.firstNotNullOfOrNull { season -> season.episodes.firstOrNull { it.id == episodeId } }
                ?: series.episodes.firstOrNull { it.id == episodeId }
            val category = UiMappers.categoryName(state, series = true, categoryId = series.categoryId)
            return TitleData(
                listOfNotNull(
                    Line("", clean(shownTitle), R.string.title_data_shown),
                    Line("series_name", clean(series.name)),
                    Line("series_id", clean(series.id)),
                    Line("category", clean(categoryText(category, series.categoryId))),
                    series.year?.let { Line("year", it.toString()) },
                    Line("tmdb", tmdbText(null, details?.info?.tmdbId, matchedTmdb, clean)),
                    episode?.let { Line("episode_title", clean(it.title)) },
                    Line("episode_id", clean(episodeId)),
                    episode?.let { Line("episode", it.code) },
                    episode?.let { Line("container_extension", MediaFormat.label(it.streamUrl) ?: "–") },
                ),
            )
        }

        private fun categoryText(name: String?, id: String) = "${name ?: "–"} ($id)"

        private fun tagText(name: String, category: String?) =
            FilmVersions.labelTags(name, category).joinToString(", ") { it.name }.ifEmpty { "–" }

        /** The provider's list's id, its details' id, the app's match: "550 · 550 · 550", "–" for each one missing. */
        private fun tmdbText(list: String?, details: String?, matched: Int?, clean: (String) -> String) =
            listOf(list, details, matched?.toString()).joinToString(" · ") { it?.takeIf { v -> v.isNotBlank() }?.let(clean) ?: "–" }

        /** The provider's extension as given, with the format the app reads ("mkv → MKV"); the address's format else. */
        private fun containerText(movie: Movie): String {
            val given = movie.containerExtension?.trim()?.takeIf { it.isNotEmpty() }
            val format = FilmVersions.container(movie)
            return when {
                given != null && format != null -> "$given → $format"
                given != null -> given
                else -> format ?: "–"
            }
        }

        /**
         * What the active playlist's login is made of: its login, password and server, and the values of its address's
         * query (an M3U link's `username=…&password=…`): masked wherever they appear.
         */
        fun secrets(state: OndoState): List<String> {
            val playlist = state.activePlaylist ?: return emptyList()
            val result = ArrayList<String>()
            playlist.username?.let(result::add)
            playlist.password?.let(result::add)
            for (address in listOfNotNull(playlist.url, playlist.epgUrl)) {
                runCatching { java.net.URI(address.trim()) }.getOrNull()?.let { uri ->
                    uri.host?.let(result::add)
                    uri.rawQuery?.split('&')?.mapNotNullTo(result) { it.substringAfter('=', "").takeIf { v -> v.isNotEmpty() } }
                    uri.rawUserInfo?.split(':')?.let(result::addAll)
                }
            }
            return result
        }
    }
}
