package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.parsing.TitleParser
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The home carousel: the titles released lately, best first by rating and freshness. A title counts from its release
 * date, never from the day the provider added it: a movie released in the last [MOVIE_DAYS] days (TMDb's release date),
 * a series with an episode aired in the last [SERIES_DAYS] days (its latest episode or season: a series from 2014 with a
 * new episode this week is in; one with nothing new is not). The same rule, constants and tests as the Apple core
 * (`RecentReleases.swift`).
 *
 * Each title's score is its weighted rating for new titles ([carouselRating]: few votes, a small prior) plus a
 * freshness bonus that falls from [FRESHNESS] on its release day to 0 at the end of its window ([freshness]). The
 * titles with at least [CAROUSEL_MINIMUM_VOTES] votes come first by score, the others after them by date; movies and
 * series take turns. Fewer titles than the carousel holds: those there are; none: the carousel is empty, never filled
 * with older titles.
 */
object RecentReleases {
    /** A movie is in the carousel this many days from its release (today included). */
    const val MOVIE_DAYS = 90L

    /** A series is in it this many days from its latest episode. */
    const val SERIES_DAYS = 30L

    /** The widest window: titles released before it are never in the carousel. */
    const val WIDEST: Long = MOVIE_DAYS

    /**
     * `m` of the carousel's weighted rating: a new title has tens or hundreds of votes, not thousands, so the prior weighs
     * as 50 votes (200 elsewhere, [WeightedRating.PRIOR_VOTES]): a film rated 8 by 300 people (7.79) passes one rated 9
     * by 20 (7.21), and ten votes at 10 stay near the prior (7.08).
     */
    const val CAROUSEL_PRIOR_VOTES = 50.0

    /** Fewer TMDb votes than this and a title is ranked by date, after the rated ones: a handful says nothing yet. */
    const val CAROUSEL_MINIMUM_VOTES = 5

    /**
     * The freshness bonus on a title's release day, falling evenly to 0 at the end of its window: half a point, the
     * gap between a good and a very good weighted rating, so freshness decides between close ratings, never over a much
     * better one.
     */
    const val FRESHNESS = 0.5

    /**
     * Fewer TMDb votes than this and a selection's daily pick ([Selections.dailyOrder]) takes the prior average for a
     * title's rating: with [WeightedRating.PRIOR_VOTES] = 200, a handful of votes says nothing yet.
     */
    const val MINIMUM_VOTES = 10

    /** A title to place: its release ([epochDay]), TMDb's votes when known, and what the carousel needs to show it. */
    data class Candidate(
        val id: String,
        val title: String,
        /** The day it was released ([epochDay]); null when only a year, or nothing, is known. */
        val released: Long?,
        val average: Double? = null,
        val votes: Int = 0,
        val categoryId: String = "",
        val hasArtwork: Boolean = true,
        /** The title it stands for ([identity]): candidates of one identity are one card. */
        val identity: String = id,
        /** A series: its window is [SERIES_DAYS], and the carousel alternates movies and series. */
        val series: Boolean = false,
    )

    /** How many days a title of this kind stays in the carousel. */
    fun window(series: Boolean): Long = if (series) SERIES_DAYS else MOVIE_DAYS

    /**
     * The weighted rating of a new title, `(v × R + m × C) / (v + m)` with `m` = [CAROUSEL_PRIOR_VOTES] and `C` =
     * [WeightedRating.PRIOR_AVERAGE]; null under [CAROUSEL_MINIMUM_VOTES] votes or without an average.
     */
    fun carouselRating(average: Double?, votes: Int): Double? {
        if (votes < CAROUSEL_MINIMUM_VOTES || average == null || average <= 0.0) return null
        val v = votes.toDouble()
        return (v * average + CAROUSEL_PRIOR_VOTES * WeightedRating.PRIOR_AVERAGE) / (v + CAROUSEL_PRIOR_VOTES)
    }

    /** The bonus of a title released [age] days ago: [FRESHNESS] on the day, falling evenly to 0 at its window's end. */
    fun freshness(age: Long, series: Boolean): Double = FRESHNESS * (1.0 - age.toDouble() / window(series).toDouble())

    /** A title's score at [today]: [carouselRating] plus [freshness]; null when it is not rated enough. */
    fun score(candidate: Candidate, today: Long): Double? {
        val released = candidate.released ?: return null
        val rating = carouselRating(candidate.average, candidate.votes) ?: return null
        return rating + freshness(today - released, candidate.series)
    }

    /**
     * What a title is, whatever the provider's entry: a series by its name cleaned ([FilmVersions.titleKey], the
     * provider's tags and qualities left out) without an episode ("Harbor S01E08", "|FR| Harbor" and "Harbor 4K" are
     * one series); a movie by its cleaned name and year (a remake is another film).
     */
    fun identity(title: String, series: Boolean, year: Int?): String {
        if (!series) return "movie:" + FilmVersions.titleKey(title) + "|" + (year?.toString() ?: "")
        val name = TitleParser.parseEpisode(title)?.seriesName ?: title
        return "series:" + FilmVersions.titleKey(name)
    }

    /**
     * One candidate per [Candidate.identity]: the one released last, then the one with more votes, then the smaller
     * id; in the order of [candidates].
     */
    fun distinctTitles(candidates: List<Candidate>): List<Candidate> {
        val best = HashMap<String, Candidate>()
        for (candidate in candidates) {
            val kept = best[candidate.identity]
            if (kept == null || isBetter(candidate, kept)) best[candidate.identity] = candidate
        }
        return candidates.filter { best[it.identity] === it }
    }

    private fun isBetter(a: Candidate, b: Candidate): Boolean {
        val aDay = a.released ?: Long.MIN_VALUE
        val bDay = b.released ?: Long.MIN_VALUE
        if (aDay != bDay) return aDay > bDay
        if (a.votes != b.votes) return a.votes > b.votes
        return a.id < b.id
    }

    /** "2026-11-02" as a day count (`LocalDate.toEpochDay`); null for anything but a full, valid date (a year alone is not one). */
    fun epochDay(date: String?): Long? {
        val text = date?.trim()?.takeIf { it.length >= 10 }?.substring(0, 10) ?: return null
        if (!Regex("""\d{4}-\d{2}-\d{2}""").matches(text)) return null
        return try {
            LocalDate.parse(text).toEpochDay()
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /**
     * The release the carousel counts: TMDb's ([TitleMetadata.releaseDate]) when it gave one, else the provider's when it
     * is a full date (a movie's info, a series' latest episode).
     */
    fun releaseDay(tmdb: String?, provider: String?): Long? = epochDay(tmdb) ?: epochDay(provider)

    /**
     * Whether a title may have been released inside the widest window, from what the playlist alone says: released this
     * year or last year, or changed by the provider lately ([addedAt], epoch seconds: a series gets a new episode). Only
     * those are asked TMDb about for the carousel ([toAsk]).
     */
    fun mayBeRecent(year: Int?, addedAt: Long?, today: Long): Boolean {
        val thisYear = LocalDate.ofEpochDay(today).year
        if (year != null && year >= thisYear - 1 && year <= thisYear + 1) return true
        val added = addedAt ?: return false
        return added / 86_400 >= today - WIDEST
    }

    /**
     * Whether TMDb is asked again about a title kept at [fetchedAt] (epoch ms) with [releaseDate]: when nothing is kept,
     * when it was kept before release dates were, or when the provider changed the title since ([addedAt], epoch seconds:
     * a series' new episode moves its latest air date).
     */
    fun needsAsking(kept: Boolean, found: Boolean, releaseDate: String?, fetchedAt: Long, addedAt: Long?): Boolean {
        if (!kept) return true
        if (!found) return false
        if (releaseDate == null) return true
        return addedAt != null && addedAt * 1_000 > fetchedAt
    }

    /**
     * Up to [size] titles for the carousel at [today] (an epoch day): those released inside their window ([window]), the
     * rated ones by [score], then the others by date, then the title and the id; movies and series take turns, the
     * kind of the first one first. Never a title released after [today], without artwork, of a [hidden] category or
     * without a full release date; one title once ([distinctTitles]).
     */
    fun pick(candidates: List<Candidate>, today: Long, size: Int, hidden: Set<String> = emptySet()): List<Candidate> {
        if (size <= 0) return emptyList()
        val released = candidates.filter { candidate ->
            val day = candidate.released
            day != null && day <= today && today - day < window(candidate.series) && candidate.hasArtwork && candidate.categoryId !in hidden
        }.distinctBy { it.id }.let(::distinctTitles)
        return alternate(rank(released, today)).take(size)
    }

    /** [candidates] in the carousel's order before movies and series take turns: by [score], then by date. */
    fun rank(candidates: List<Candidate>, today: Long): List<Candidate> {
        class Keyed(val candidate: Candidate, val score: Double?)
        val order = Comparator<Keyed> { a, b ->
            when {
                a.score != null && b.score != null && a.score != b.score -> return@Comparator b.score.compareTo(a.score)
                a.score != null && b.score == null -> return@Comparator -1
                a.score == null && b.score != null -> return@Comparator 1
            }
            val aDay = a.candidate.released ?: Long.MIN_VALUE
            val bDay = b.candidate.released ?: Long.MIN_VALUE
            if (aDay != bDay) return@Comparator bDay.compareTo(aDay)
            String.CASE_INSENSITIVE_ORDER.compare(a.candidate.title, b.candidate.title).takeIf { it != 0 } ?: a.candidate.id.compareTo(b.candidate.id)
        }
        return candidates.map { Keyed(it, score(it, today)) }.sortedWith(order).map { it.candidate }
    }

    /** Movies and series in turn, each kind in its order, the kind of the first one first; the rest of one kind at the end. */
    fun alternate(ranked: List<Candidate>): List<Candidate> {
        val first = ranked.firstOrNull() ?: return emptyList()
        val leading = ranked.filter { it.series == first.series }
        val other = ranked.filter { it.series != first.series }
        val result = ArrayList<Candidate>(ranked.size)
        for (index in 0 until maxOf(leading.size, other.size)) {
            leading.getOrNull(index)?.let(result::add)
            other.getOrNull(index)?.let(result::add)
        }
        return result
    }
}
