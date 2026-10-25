package com.markedoe.ondo.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * Opens a trailer on YouTube: in a YouTube app (the phone's, or the TV's, where no browser is opened), else at
 * youtube.com. False when nothing on the device opens either.
 */
fun Context.openTrailer(youTubeId: String): Boolean {
    for (address in listOf("vnd.youtube:$youTubeId", "https://www.youtube.com/watch?v=$youTubeId")) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, address.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: ActivityNotFoundException) {
            // On to the next way.
        }
    }
    return false
}
