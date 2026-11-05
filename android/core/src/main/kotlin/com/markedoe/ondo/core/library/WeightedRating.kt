package com.markedoe.ondo.core.library

/**
 * The home carousel's "best recent" ranking: TMDb's average vote weighted by how many gave it (a Bayesian weighted
 * rating), so a title rated 10 by ten people does not beat one rated 7.8 by thousands. The same rule, constants and
 * tests as the Apple core (`WeightedRating.swift`).
 *
 * `score = v / (v + m) * R + m / (v + m) * C`, with `R` the title's average, `v` its votes, `m` = 200 and `C` = 6.5.
 * A title needs at least 50 votes to qualify at all.
 */
object WeightedRating {
    /** `m`: the votes a title needs for its own average to weigh as much as the prior. */
    const val PRIOR_VOTES = 200.0

    /** `C`: the average assumed for a title nobody has rated. */
    const val PRIOR_AVERAGE = 6.5

    /** Fewer votes than this and a title does not qualify. */
    const val MINIMUM_VOTES = 50

    /** How far back (in days) the carousel looks for titles the provider added. */
    const val RECENT_DAYS = 4L

    /** Whether a title added at [addedAt] is inside the carousel's window at [now] (both epoch seconds; exactly [RECENT_DAYS] days ago is in). */
    fun isRecent(addedAt: Long, now: Long): Boolean = addedAt >= now - RECENT_DAYS * 86_400L

    /** A title to rank: TMDb's `vote_average` and `vote_count`, and when the provider added it (epoch seconds). */
    data class Candidate(val id: String, val title: String, val average: Double, val votes: Int, val addedAt: Long)

    /** The weighted rating, null when the title does not qualify (fewer than [MINIMUM_VOTES] votes, or no rating). */
    fun score(average: Double, votes: Int): Double? {
        if (votes < MINIMUM_VOTES) return null
        return weighted(average, votes)
    }

    /**
     * The same weighted rating without the carousel's floor of [MINIMUM_VOTES] (the order of a grid sorted by rating: a
     * title with few votes stays near [PRIOR_AVERAGE]), null without a rating or a vote.
     */
    fun weighted(average: Double, votes: Int): Double? {
        if (votes <= 0 || average <= 0.0) return null
        val v = votes.toDouble()
        return v / (v + PRIOR_VOTES) * average + PRIOR_VOTES / (v + PRIOR_VOTES) * PRIOR_AVERAGE
    }

    /**
     * The order of a grid sorted by rating. The titles TMDb rated come first, by their weighted rating ([weighted]), then
     * by more votes; the others after them, by the provider's rating (none last): the two scales never mix. Ties go to
     * the title (without regard to case), then to the id, so the order never depends on the list's.
     *
     * [tmdb]: TMDb's rating of an item, null when unknown (no token, not asked yet, not found); [provider]: the
     * provider's rating of an item.
     */
    fun <T> ratingOrder(
        items: List<T>,
        tmdb: (T) -> TmdbRating?,
        provider: (T) -> Double?,
        title: (T) -> String,
        id: (T) -> String,
    ): List<T> {
        // Each read once, not at every comparison.
        class Keyed<I>(val item: I, val score: Double?, val votes: Int, val provider: Double?, val title: String, val id: String)
        val keyed = items.map { item ->
            val rating = tmdb(item)
            val score = rating?.let { weighted(it.average, it.votes) }
            Keyed(item, score, if (score == null) 0 else rating!!.votes, provider(item)?.takeIf { it > 0 }, title(item), id(item))
        }
        val order = Comparator<Keyed<T>> { a, b ->
            when {
                a.score != null && b.score != null -> {
                    if (a.score != b.score) return@Comparator b.score.compareTo(a.score)
                    if (a.votes != b.votes) return@Comparator b.votes.compareTo(a.votes)
                }
                a.score != null -> return@Comparator -1
                b.score != null -> return@Comparator 1
                a.provider != null && b.provider != null -> if (a.provider != b.provider) return@Comparator b.provider.compareTo(a.provider)
                a.provider != null -> return@Comparator -1
                b.provider != null -> return@Comparator 1
            }
            String.CASE_INSENSITIVE_ORDER.compare(a.title, b.title).takeIf { it != 0 } ?: a.id.compareTo(b.id)
        }
        return keyed.sortedWith(order).map { it.item }
    }

    /** The carousel's pick: the candidates added inside the window at [now] (epoch seconds) that qualify, best first ([rank]). */
    fun bestRecent(candidates: List<Candidate>, now: Long): List<Candidate> = rank(candidates.filter { isRecent(it.addedAt, now) })

    /** The qualifying candidates, best first: highest score, then latest added, then title (without regard to case). */
    fun rank(candidates: List<Candidate>): List<Candidate> =
        candidates
            .mapNotNull { candidate -> score(candidate.average, candidate.votes)?.let { candidate to it } }
            .sortedWith(
                compareByDescending<Pair<Candidate, Double>> { it.second }
                    .thenByDescending { it.first.addedAt }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.first.title },
            )
            .map { it.first }
}
