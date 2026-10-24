package com.markedoe.ondo.player

/** A channel of the player's Channels list: its number and name, the programme on now, whether it plays. */
data class ChannelChoice(val id: String, val number: Int?, val name: String, val now: String?, val playing: Boolean) {
    /** "12  World Report", or the name alone. */
    val label: String get() = listOfNotNull(number?.toString(), name).joinToString("  ")
}

/**
 * The channels a live channel's player zaps through, for its Channels list (a sheet on a phone, a side panel on a TV):
 * read when the list opens ([load], each with the programme on now), and the one chosen ([onChoose], by its id).
 */
class ChannelList(val load: () -> List<ChannelChoice>, val onChoose: (String) -> Unit)
