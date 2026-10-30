package com.markedoe.ondo.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.markedoe.ondo.R

/** Where trailers play: the label says it, so the viewer knows Ondo hands over to it. */
const val TRAILER_SITE = "YouTube"

/** "Trailer", and beneath it where it plays. */
@Composable
fun trailerLabel(): String = stringResource(R.string.trailer) + "\n" + TRAILER_SITE

/** The YouTube app of Android TV: it opens a video from its watch address (its other links open its home). */
internal const val YOUTUBE_TV_PACKAGE = "com.google.android.youtube.tv"

/**
 * The ways a trailer is opened, in order: on a TV, the watch address in the TV's YouTube app, then in whatever opens
 * it; on a phone, the YouTube app's own link first (the watch address could open a browser chooser).
 */
internal fun trailerIntents(youTubeId: String, television: Boolean, hasTvApp: Boolean): List<Intent> {
    val watch = "https://www.youtube.com/watch?v=$youTubeId".toUri()
    val app = "vnd.youtube:$youTubeId".toUri()
    fun view(uri: android.net.Uri, pkg: String? = null) = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { pkg?.let(::setPackage) }
    return if (television) {
        listOfNotNull(view(watch, YOUTUBE_TV_PACKAGE).takeIf { hasTvApp }, view(watch), view(app))
    } else {
        listOf(view(app), view(watch))
    }
}

/**
 * Opens a trailer on YouTube: in a YouTube app (the phone's, or the TV's, where no browser is opened), else at
 * youtube.com. False when nothing on the device opens either.
 */
fun Context.openTrailer(youTubeId: String): Boolean {
    val television = (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    val hasTvApp = runCatching { packageManager.getApplicationInfo(YOUTUBE_TV_PACKAGE, 0) }.isSuccess
    for (intent in trailerIntents(youTubeId, television, hasTvApp)) {
        try {
            startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            // On to the next way.
        }
    }
    return false
}
