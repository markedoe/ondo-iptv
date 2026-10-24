package com.markedoe.ondo.player

import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.markedoe.ondo.R
import com.markedoe.ondo.data.VideoScale

/** The name of the aspect ratio in the player's menus. */
@get:StringRes
internal val VideoScale.label: Int
    get() = when (this) {
        VideoScale.FIT -> R.string.scale_fit
        VideoScale.FILL -> R.string.scale_fill
        VideoScale.STRETCH -> R.string.scale_stretch
    }

/** Media3's resize mode for it: the whole picture, cropped to fill the screen, or stretched to it. */
internal val VideoScale.resizeMode: Int
    @OptIn(UnstableApi::class)
    get() = when (this) {
        VideoScale.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        VideoScale.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        VideoScale.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    }
