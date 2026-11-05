package com.markedoe.ondo.core.library

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The home carousel: the titles released lately, the best first by TMDb's weighted rating ([WeightedRating.weighted]).
 * A title counts from its release date, never from the day the provider added it: a movie's release, a series' latest
 * episode or season (a series from 2014 with a new season this week is new; one with nothing new is not). The same rule,
 * constants and tests as the Apple core (`RecentReleases.swift`).
 *
 * The window is the last [WINDOWS] days, never a date in the future; it widens step by step while fewer titles than the
 * carousel holds qualify. When none qualifies at the widest, the newest releases by date stand in (the carousel stays
 * among recent releases); with none at all the carousel is empty, never filled with older titles.
 */
object RecentReleases {
    /** The windows tried in turn (days, today included), until enough titles qualify. */
    val WINDOWS = listOf(7L, 14L, 30L, 60L)

    /** The widest window: titles released before it are never in the carousel. */
    val WIDEST: Long get() = WINDOWS.last()

    /**
     * Fewer TMDb votes than this and a title does not qualify for the ranking. New releases have few votes: the weight
     * ([WeightedRating.PRIOR_VOTES] = 200) already keeps a title with a handful near the prior (10 votes at 10/10 score
     * 6.67), so the floor only keeps out the first few votes of a title nobody has seen yet.
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
    )

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
     * Up to [size] titles for the carousel at [today] (an epoch day): those released in the first window of [WINDOWS] that
     * gives [size] qualifying titles (at least [MINIMUM_VOTES] votes), else the widest's, best first; when none qualifies,
     * the newest releases of the widest window by date. Never a title released after [today], without artwork, of a
     * [hidden] category or without a full release date.
     */
    fun pick(candidates: List<Candidate>, today: Long, size: Int, hidden: Set<String> = emptySet()): List<Candidate> {
        if (size <= 0) return emptyList()
        val released = candidates.filter { candidate ->
            val day = candidate.released
            day != null && day <= today && candidate.hasArtwork && candidate.categoryId !in hidden
        }.distinctBy { it.id }
        var best: List<Candidate> = emptyList()
        for (days in WINDOWS) {
            best = rank(released.filter { today - it.released!! < days })
            if (best.size >= size) return best.take(size)
        }
        if (best.isNotEmpty()) return best
        return released.filter { today - it.released!! < WIDEST }.sortedWith(newestFirst).take(size)
    }

    /** The candidates with enough votes, best first: the weighted rating, then the newest, then the title, then the id. */
    fun rank(candidates: List<Candidate>): List<Candidate> =
        candidates
            .mapNotNull { candidate ->
                if (candidate.votes < MINIMUM_VOTES) return@mapNotNull null
                WeightedRating.weighted(candidate.average ?: 0.0, candidate.votes)?.let { candidate to it }
            }
            .sortedWith(
                compareByDescending<Pair<Candidate, Double>> { it.second }
                    .thenByDescending { it.first.released }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.first.title }
                    .thenBy { it.first.id },
            )
            .map { it.first }

    private val newestFirst = compareByDescending<Candidate> { it.released }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        .thenBy { it.id }
}
