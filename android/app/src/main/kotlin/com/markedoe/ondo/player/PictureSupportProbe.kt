package com.markedoe.ondo.player

import android.app.Activity
import android.view.Display
import com.markedoe.ondo.core.library.PictureSupport

/**
 * What this screen shows, for the version of a film that plays best (`FilmVersions.defaultVersion`): the HDR formats
 * Android says the display takes (Dolby Vision, HDR10, HDR10+, HLG) and whether one of its modes is 4K.
 */
object PictureSupportProbe {
    fun of(activity: Activity): PictureSupport = runCatching {
        @Suppress("DEPRECATION")
        val display = activity.windowManager.defaultDisplay
        @Suppress("DEPRECATION")
        val types = display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
        PictureSupport(
            hdr = types.any { it == Display.HdrCapabilities.HDR_TYPE_HDR10 || it == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS || it == Display.HdrCapabilities.HDR_TYPE_HLG },
            dolbyVision = types.any { it == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION },
            uhd = display.supportedModes.any { maxOf(it.physicalWidth, it.physicalHeight) >= 3840 },
        )
    }.getOrDefault(PictureSupport())
}
