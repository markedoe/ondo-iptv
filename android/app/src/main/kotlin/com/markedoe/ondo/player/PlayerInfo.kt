package com.markedoe.ondo.player

/** What the player's top lines say, on a TV and on a phone. */
data class PlayerInfo(
    /** The movie, the series or the channel. */
    val title: String,
    /** The episode's code and title, a movie's year, or the programme on now on a live channel. */
    val subtitle: String?,
    val live: Boolean,
    /** A live channel's programme on now, from the guide: its start and end (epoch milliseconds). */
    val programmeStart: Long? = null,
    val programmeEnd: Long? = null,
)
