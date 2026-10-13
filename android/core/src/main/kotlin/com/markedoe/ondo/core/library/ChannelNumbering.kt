package com.markedoe.ondo.core.library

/** Channel numbers as shown in lists and typed on a keyboard or remote ("zapping by number"). */
object ChannelNumbering {
    /**
     * Number displayed for the channel at `position` (0-based) of a list. Lists where any channel has an
     * explicit number (`tvg-chno`, Xtream `num`) show those numbers; other lists are numbered by position.
     */
    fun displayNumber(position: Int, numbers: List<Int?>): Int? {
        if (position !in numbers.indices) return null
        return if (usesExplicitNumbers(numbers)) numbers[position] else position + 1
    }

    /** Position of the channel the user typed `number` for, matching [displayNumber]. */
    fun position(number: Int, numbers: List<Int?>): Int? {
        if (usesExplicitNumbers(numbers)) return numbers.indexOf(number).takeIf { it >= 0 }
        return (number - 1).takeIf { it in numbers.indices }
    }

    private fun usesExplicitNumbers(numbers: List<Int?>): Boolean = numbers.any { it != null }
}
