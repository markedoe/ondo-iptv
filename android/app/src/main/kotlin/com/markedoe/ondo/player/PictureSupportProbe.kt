package com.markedoe.ondo.player

import android.app.Activity
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import com.markedoe.ondo.core.library.PictureSupport
import java.util.concurrent.Executors

/**
 * What the app reads of the screen and the decoders, for the version of a film that plays best
 * (`FilmVersions.defaultChoice`) and the title data panel: the mode of the moment and the largest mode the screen
 * offers (physical pixels, never the size the interface is drawn at: a 4K box often draws a 1080p interface, and in
 * "auto" can report a 1080p mode until a film plays), the HDR formats the display takes, and whether a Dolby Vision and
 * an HEVC Main 10 decoder exist ([dolbyVisionDecoder], [hevcMain10Decoder]: null until checked, off the main thread).
 */
data class DisplayFacts(
    val currentWidth: Int = 0,
    val currentHeight: Int = 0,
    val largestWidth: Int = 0,
    val largestHeight: Int = 0,
    /** `Display.HdrCapabilities.HDR_TYPE_*` the display takes, as `Display.getHdrCapabilities` says (without the types the viewer turned off). */
    val hdrTypes: Set<Int> = emptySet(),
    val dolbyVisionDecoder: Boolean? = null,
    val hevcMain10Decoder: Boolean? = null,
) {
    val hdr10: Boolean get() = Display.HdrCapabilities.HDR_TYPE_HDR10 in hdrTypes
    val hdr10Plus: Boolean get() = Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in hdrTypes
    val hlg: Boolean get() = Display.HdrCapabilities.HDR_TYPE_HLG in hdrTypes

    /** The display says it shows Dolby Vision (a box's decoder alone does not make a screen show it). */
    val dolbyVisionDisplay: Boolean get() = Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in hdrTypes

    /**
     * What the versions are chosen for: HDR when the display takes HDR10, HDR10+ or HLG; Dolby Vision when the display
     * says it shows it and a decoder for it exists (or is not checked yet); 4K when its largest mode (or the mode of the
     * moment) is 4K, whatever the mode of the moment reads.
     */
    fun pictureSupport(): PictureSupport = PictureSupport(
        hdr = hdr10 || hdr10Plus || hlg,
        dolbyVision = dolbyVisionDisplay && dolbyVisionDecoder != false,
        uhd = maxOf(largestWidth, largestHeight, currentWidth, currentHeight) >= UHD_WIDTH,
    )

    companion object {
        /** A 4K mode's longer side. */
        const val UHD_WIDTH = 3840

        /**
         * The facts of a display: its mode of the moment ([current], physical width and height), its supported modes
         * ([modes]), the HDR types it reports ([hdrTypes]: its capabilities').
         */
        fun of(
            current: Pair<Int, Int>,
            modes: List<Pair<Int, Int>>,
            hdrTypes: Collection<Int>,
            dolbyVisionDecoder: Boolean? = null,
            hevcMain10Decoder: Boolean? = null,
        ): DisplayFacts {
            val largest = (modes + current).maxByOrNull { it.first.toLong() * it.second } ?: (0 to 0)
            return DisplayFacts(
                currentWidth = current.first,
                currentHeight = current.second,
                largestWidth = largest.first,
                largestHeight = largest.second,
                hdrTypes = hdrTypes.toSet(),
                dolbyVisionDecoder = dolbyVisionDecoder,
                hevcMain10Decoder = hevcMain10Decoder,
            )
        }

        /** Whether [decoders] (each one's MIME types and, by type, its profiles) hold a Dolby Vision and an HEVC Main 10 one. */
        fun decoders(decoders: List<Map<String, List<Int>>>): Pair<Boolean, Boolean> {
            val dolbyVision = decoders.any { types -> types.keys.any { it.equals(DOLBY_VISION_MIME, ignoreCase = true) } }
            val main10 = decoders.any { types ->
                types.entries.any { (type, profiles) -> type.equals(HEVC_MIME, ignoreCase = true) && HEVC_MAIN10 in profiles }
            }
            return dolbyVision to main10
        }

        const val DOLBY_VISION_MIME = "video/dolby-vision"
        const val HEVC_MIME = "video/hevc"
        const val HEVC_MAIN10 = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
    }
}

/** Reads [DisplayFacts] of the screen the app shows on: the display at once, the decoders once, in the background. */
object PictureSupportProbe {
    /** How long after the screen is read the decoders are, so the first Home is drawn before. */
    private const val DECODERS_DELAY_MS = 3_000L

    /** What was read last; null before an activity read it. */
    @Volatile var facts: DisplayFacts? = null
        private set

    /** The decoders, read once per process. */
    @Volatile private var decoders: Pair<Boolean, Boolean>? = null
    /** The decoders are read at the lowest priority: never in the way of the launch, the first Home or a stream. */
    private val background = Executors.newSingleThreadExecutor { task ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST)
            task.run()
        }, "decoders").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    /**
     * The facts of [activity]'s display (cheap: no decoder list on the main thread); the decoders are read once, in the
     * background, then [onDecoders] gets the facts with them.
     */
    fun of(activity: Activity, onDecoders: (DisplayFacts) -> Unit = {}): DisplayFacts {
        // A short wait first: the first Home is drawn before the decoder list is read.
        val read = runCatching { read(activity) }.getOrDefault(DisplayFacts())
        val known = decoders
        val facts = if (known != null) read.copy(dolbyVisionDecoder = known.first, hevcMain10Decoder = known.second) else read
        this.facts = facts
        if (known == null) {
            background.execute {
                runCatching { Thread.sleep(DECODERS_DELAY_MS) }
                val found = runCatching { listDecoders() }.getOrNull() ?: return@execute
                decoders = found
                val withDecoders = (this.facts ?: facts).copy(dolbyVisionDecoder = found.first, hevcMain10Decoder = found.second)
                this.facts = withDecoders
                onDecoders(withDecoders)
            }
        }
        return facts
    }

    @Suppress("DEPRECATION")
    private fun read(activity: Activity): DisplayFacts {
        val display = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else null) ?: activity.windowManager.defaultDisplay
        val mode = display.mode
        val modes = display.supportedModes.orEmpty()
        // The display's capabilities, which leave out the HDR types the viewer turned off (Android 14 and later); never
        // each mode's own list, which does not.
        val types = HashSet<Int>()
        display.hdrCapabilities?.supportedHdrTypes?.forEach { types.add(it) }
        return DisplayFacts.of(
            current = mode.physicalWidth to mode.physicalHeight,
            modes = modes.map { it.physicalWidth to it.physicalHeight },
            hdrTypes = types,
        )
    }

    private fun listDecoders(): Pair<Boolean, Boolean> {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        return DisplayFacts.decoders(
            infos.map { info ->
                info.supportedTypes.associateWith { type ->
                    runCatching { info.getCapabilitiesForType(type).profileLevels.map { it.profile } }.getOrDefault(emptyList())
                }
            },
        )
    }
}
