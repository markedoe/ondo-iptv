package com.markedoe.ondo.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources
import com.markedoe.ondo.R

/** What remains of a movie or an episode: "45 min left", from an hour on "1 h 23 min left" (or "2 h left"). */
fun timeLeft(resources: Resources, minutes: Int): String {
    if (minutes < 60) return resources.getQuantityString(R.plurals.minutes_left, minutes, minutes)
    val hours = minutes / 60
    val rest = minutes % 60
    return if (rest == 0) resources.getQuantityString(R.plurals.hours_left, hours, hours)
    else resources.getQuantityString(R.plurals.hours_minutes_left, hours, hours, rest)
}

/** [timeLeft] in the language of the interface. */
@Composable
@ReadOnlyComposable
fun timeLeftText(minutes: Int): String = timeLeft(LocalResources.current, minutes)
