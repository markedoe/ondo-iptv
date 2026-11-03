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
        if (votes < MINIMUM_VOTES || average <= 0.0) return null
        val v = votes.toDouble()
        return v / (v + PRIOR_VOTES) * average + PRIOR_VOTES / (v + PRIOR_VOTES) * PRIOR_AVERAGE
    }

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
