package com.markedoe.ondo.player

import androidx.annotation.StringRes
import com.markedoe.ondo.R
import com.markedoe.ondo.data.SubtitleSize

/** The subtitle size in use and the way to change it, for the player's More panel. */
class SubtitleSizing(val size: SubtitleSize, val onChange: (SubtitleSize) -> Unit)

/** The name of the size in the player's menus. */
@get:StringRes
internal val SubtitleSize.label: Int
    get() = when (this) {
        SubtitleSize.SMALL -> R.string.size_small
        SubtitleSize.NORMAL -> R.string.size_normal
        SubtitleSize.LARGE -> R.string.size_large
        SubtitleSize.EXTRA_LARGE -> R.string.size_extra_large
    }

/**
 * The subtitles' height as a fraction of the video's, as Media3's view takes it: its own default, times the size of
 * Android's caption settings ([captionScale]), times the viewer's choice.
 */
internal fun SubtitleSize.fraction(default: Float, captionScale: Float): Float = default * captionScale * scale
