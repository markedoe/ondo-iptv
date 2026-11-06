package com.markedoe.ondo.ui

import android.content.res.Resources
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources
import com.markedoe.ondo.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale

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

/**
 * "Updated 12 minutes ago", "Updated 3 hours ago", "Updated 2 days ago", then the date: when the playlist was last
 * fetched, in the app's words (the system's relative dates follow the system language, not the app's). At least a
 * minute: "0 minutes ago" reads oddly right after a refresh.
 */
fun updatedAgo(resources: Resources, updatedAt: Long, now: Long, locale: Locale): String {
    val minutes = ((now - updatedAt) / 60_000L).coerceAtLeast(1L)
    val ago = when {
        minutes < 60 -> resources.getQuantityString(R.plurals.minutes_ago, minutes.toInt(), minutes.toInt())
        minutes < 24 * 60 -> (minutes / 60).toInt().let { resources.getQuantityString(R.plurals.hours_ago, it, it) }
        minutes < 7 * 24 * 60 -> (minutes / (24 * 60)).toInt().let { resources.getQuantityString(R.plurals.days_ago, it, it) }
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(updatedAt))
    }
    return resources.getString(R.string.updated_ago, ago)
}

/** [updatedAgo] in the language of the interface. */
@Composable
@ReadOnlyComposable
fun updatedAgoText(updatedAt: Long, now: Long): String =
    LocalResources.current.let { updatedAgo(it, updatedAt, now, it.configuration.locales[0]) }

/** The longest [shortAge] counts in days ("99 d" for anything older). */
const val SHORT_AGE_MAX_DAYS = 99L

/**
 * How long ago the playlist was updated, as short as it gets: "<1 min", "5 min", "2 hr", "3 days" in English, "3 j" in
 * French, in the units and digits of [locale] (ICU's own abbreviations). The longest unit is the day, up to
 * [SHORT_AGE_MAX_DAYS].
 */
fun shortAge(updatedAt: Long, now: Long, locale: Locale): String {
    val minutes = (now - updatedAt) / 60_000L
    val format = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT)
    return when {
        minutes < 1 -> "<" + format.format(Measure(1, MeasureUnit.MINUTE))
        minutes < 60 -> format.format(Measure(minutes, MeasureUnit.MINUTE))
        minutes < 24 * 60 -> format.format(Measure(minutes / 60, MeasureUnit.HOUR))
        else -> format.format(Measure((minutes / (24 * 60)).coerceAtMost(SHORT_AGE_MAX_DAYS), MeasureUnit.DAY))
    }
}

/** The widest values [shortAge] gives in [locale], one of each unit: the room its slot keeps whatever it shows. */
fun shortAgeSamples(locale: Locale): List<String> {
    val now = 1_000L * 60 * 60 * 24 * 365
    val minute = 60_000L
    return listOf(0L, 59 * minute, 23 * 60 * minute, SHORT_AGE_MAX_DAYS * 24 * 60 * minute).map { shortAge(now - it, now, locale) }
}
