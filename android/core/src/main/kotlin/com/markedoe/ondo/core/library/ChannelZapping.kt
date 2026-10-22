package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.model.Channel

/**
 * Zapping in the player: the channel after or before the one playing, and the channel of a number typed on the
 * remote, in [channels] as Live TV lists them. The list wraps around: past the last channel comes the first.
 */
object ChannelZapping {
    /** The channel [by] places after (negative: before) the one with [fromId]; the first or last when it is gone. */
    fun step(channels: List<Channel>, fromId: String, by: Int): Channel? {
        if (channels.isEmpty() || by == 0) return null
        val index = channels.indexOfFirst { it.id == fromId }
        if (index < 0) return if (by > 0) channels.first() else channels.last()
        return channels[Math.floorMod(index + by, channels.size)]
    }

    /** The channel shown with [number] in the list ([ChannelNumbering]: its own numbers, else positions). */
    fun byNumber(channels: List<Channel>, number: Int): Channel? =
        ChannelNumbering.position(number, channels.map { it.number })?.let(channels::get)
}
