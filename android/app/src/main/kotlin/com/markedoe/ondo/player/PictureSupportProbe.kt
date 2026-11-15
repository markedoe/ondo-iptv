package com.markedoe.ondo.player

import android.app.Activity
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.markedoe.ondo.core.library.PictureSupport
import java.util.concurrent.Executors

/**
 * What the app reads of the screen and the decoders, for the version of a film that plays best
 * (`FilmVersions.defaultChoice`) and the title data panel: the mode of the moment and the largest mode the screen
 * offers (physical pixels, never the size the interface is drawn at: a 4K box often draws a 1080p interface, and in
 * "auto" can report a 1080p mode until a film plays), the HDR formats the display takes, and whether a Dolby Vision and
 * an HEVC Main 10 decoder exist ([dolbyVisionDecoder], [hevcMain10Decoder]: null until checked, off the main thread).
 * A box in "auto" may list its 1080p interface mode only, so the size Media3 reads of a TV ([displaySizeWidth]) and a
 * hardware decoder for 4K ([uhdDecoder]) are read too, in the same background task; [uhdSource] says which one made 4K.
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
    /**
     * The size of the display's mode as Media3 reads it (`Util.getCurrentDisplayModeSize`): on a TV the
     * `vendor.display-size` (`sys.display-size` before Android 9) property the box writes, or a Sony BRAVIA's 4K panel
     * feature, else the mode of the moment. 0 until read, off the main thread.
     */
    val displaySizeWidth: Int = 0,
    val displaySizeHeight: Int = 0,
    /** Whether the app runs on a TV (`UiModeManager`); null until read. */
    val television: Boolean? = null,
    /** Whether a hardware HEVC or Dolby Vision decoder takes 3840×2160 (`VideoCapabilities.isSizeSupported`); null until checked. */
    val uhdDecoder: Boolean? = null,
) {
    val hdr10: Boolean get() = Display.HdrCapabilities.HDR_TYPE_HDR10 in hdrTypes
    val hdr10Plus: Boolean get() = Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in hdrTypes
    val hlg: Boolean get() = Display.HdrCapabilities.HDR_TYPE_HLG in hdrTypes

    /** The display says it shows Dolby Vision (a box's decoder alone does not make a screen show it). */
    val dolbyVisionDisplay: Boolean get() = Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in hdrTypes

    /** Where 4K was read from, for the title data panel. */
    enum class UhdSource {
        /** The largest mode the display lists (or its mode of the moment) is 4K. */
        MODES,

        /** The size Media3 reads of a TV's display ([displaySizeWidth]: its `display-size` property) is 4K. */
        DISPLAY_SIZE,

        /** A TV whose display takes HDR, with a hardware decoder for 4K ([uhdDecoder]), its modes saying 1080p only. */
        HDR_AND_DECODER,
    }

    /**
     * Why this is a 4K screen; null when nothing says so. In this order: the modes (physical sizes) the display lists;
     * else the size Media3 reads of a TV (a box in "auto" may list its 1080p interface mode only, while its
     * `display-size` property says 3840x2160); else, on a TV only, a display that takes HDR (HDR10, HDR10+, HLG, Dolby
     * Vision) with a hardware decoder for 3840×2160: HDR TVs are 4K nearly always and a box with a 4K decoder drives
     * them in 4K. A 1080p HDR TV behind a 4K box then reads as 4K: its versions in 4K still play, downscaled. Never a
     * phone or a tablet on that last rule (an HDR phone with a 4K decoder is not a 4K screen), never a software decoder.
     */
    val uhdSource: UhdSource?
        get() = when {
            maxOf(largestWidth, largestHeight, currentWidth, currentHeight) >= UHD_WIDTH -> UhdSource.MODES
            maxOf(displaySizeWidth, displaySizeHeight) >= UHD_WIDTH -> UhdSource.DISPLAY_SIZE
            television == true && uhdDecoder == true && (hdr10 || hdr10Plus || hlg || dolbyVisionDisplay) -> UhdSource.HDR_AND_DECODER
            else -> null
        }

    /** Whether the background facts ([television], [uhdDecoder]) are read, so a 4K not found yet is a "no". */
    val uhdChecked: Boolean get() = uhdSource != null || (television != null && uhdDecoder != null)

    /**
     * What the versions are chosen for: HDR when the display takes HDR10, HDR10+ or HLG; Dolby Vision when the display
     * says it shows it and a decoder for it exists (or is not checked yet); 4K when [uhdSource] finds it, whatever the
     * mode of the moment reads.
     */
    fun pictureSupport(): PictureSupport = PictureSupport(
        hdr = hdr10 || hdr10Plus || hlg,
        dolbyVision = dolbyVisionDisplay && dolbyVisionDecoder != false,
        uhd = uhdSource != null,
    )

    /** These facts with what the background read ([found]). */
    fun with(found: Background): DisplayFacts = copy(
        dolbyVisionDecoder = found.dolbyVisionDecoder,
        hevcMain10Decoder = found.hevcMain10Decoder,
        displaySizeWidth = found.displaySize.first,
        displaySizeHeight = found.displaySize.second,
        television = found.television,
        uhdDecoder = found.uhdDecoder,
    )

    /** What is read once per process, in the background: the decoders, the size Media3 reads of the display, a TV or not. */
    data class Background(
        val dolbyVisionDecoder: Boolean,
        val hevcMain10Decoder: Boolean,
        val uhdDecoder: Boolean,
        val displaySize: Pair<Int, Int>,
        val television: Boolean,
    )

    /** A decoder as [decoderFacts] reads it: its MIME types and, by type, its profiles; hardware or not; the types it takes at 3840×2160. */
    data class Decoder(val profiles: Map<String, List<Int>>, val hardware: Boolean = true, val uhdTypes: Set<String> = emptySet())

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
        fun decoders(decoders: List<Map<String, List<Int>>>): Pair<Boolean, Boolean> =
            decoderFacts(decoders.map { Decoder(it) }).let { (dolbyVision, main10, _) -> dolbyVision to main10 }

        /** Whether [decoders] hold a Dolby Vision one, an HEVC Main 10 one, and a hardware HEVC or Dolby Vision one for 3840×2160. */
        fun decoderFacts(decoders: List<Decoder>): Triple<Boolean, Boolean, Boolean> {
            val dolbyVision = decoders.any { decoder -> decoder.profiles.keys.any { it.equals(DOLBY_VISION_MIME, ignoreCase = true) } }
            val main10 = decoders.any { decoder ->
                decoder.profiles.entries.any { (type, profiles) -> type.equals(HEVC_MIME, ignoreCase = true) && HEVC_MAIN10 in profiles }
            }
            val uhd = decoders.any { decoder ->
                decoder.hardware && decoder.uhdTypes.any { it.equals(HEVC_MIME, ignoreCase = true) || it.equals(DOLBY_VISION_MIME, ignoreCase = true) }
            }
            return Triple(dolbyVision, main10, uhd)
        }

        /**
         * Whether a decoder named [name] runs on the hardware: Android's word from Android 10, else not one of the
         * system's software ones (their names are public: `OMX.google.`, `c2.android.`).
         */
        fun hardware(name: String, hardwareAccelerated: Boolean?): Boolean = hardwareAccelerated
            ?: !(name.startsWith("OMX.google.", ignoreCase = true) || name.startsWith("c2.android.", ignoreCase = true) || name.startsWith("c2.google.", ignoreCase = true))

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

    /** The decoders and the display's size as Media3 reads it, read once per process. */
    @Volatile private var decoders: DisplayFacts.Background? = null
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
     * The facts of [activity]'s display (cheap: no decoder list and no system property on the main thread); the
     * decoders, the display's size as Media3 reads it and whether this is a TV are read once, in the background, then
     * [onDecoders] gets the facts with them.
     */
    fun of(activity: Activity, onDecoders: (DisplayFacts) -> Unit = {}): DisplayFacts {
        // A short wait first: the first Home is drawn before the decoder list is read.
        val read = runCatching { read(activity) }.getOrDefault(DisplayFacts())
        val known = decoders
        val facts = if (known != null) read.with(known) else read
        this.facts = facts
        if (known == null) {
            val context = activity.applicationContext
            val display = runCatching { display(activity) }.getOrNull()
            background.execute {
                runCatching { Thread.sleep(DECODERS_DELAY_MS) }
                val found = runCatching { readBackground(context, display) }.getOrNull() ?: return@execute
                decoders = found
                val withDecoders = (this.facts ?: facts).with(found)
                this.facts = withDecoders
                onDecoders(withDecoders)
            }
        }
        return facts
    }

    @Suppress("DEPRECATION")
    private fun display(activity: Activity): Display =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else null) ?: activity.windowManager.defaultDisplay

    private fun read(activity: Activity): DisplayFacts {
        val display = display(activity)
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

    /**
     * Off the main thread: the decoders, and the display's size as Media3 reads it (its public
     * `Util.getCurrentDisplayModeSize`, the one its track selector uses: on a TV the `display-size` property a box
     * writes; Media3 reads it, the app looks up nothing by name).
     */
    @OptIn(UnstableApi::class)
    private fun readBackground(context: Context, display: Display?): DisplayFacts.Background {
        val (dolbyVision, main10, uhd) = listDecoders()
        val television = runCatching { Util.isTv(context) }.getOrDefault(false)
        val size = display?.let { runCatching { Util.getCurrentDisplayModeSize(context, it) }.getOrNull() }
        return DisplayFacts.Background(
            dolbyVisionDecoder = dolbyVision,
            hevcMain10Decoder = main10,
            uhdDecoder = uhd,
            displaySize = (size?.x ?: 0) to (size?.y ?: 0),
            television = television,
        )
    }

    private fun listDecoders(): Triple<Boolean, Boolean, Boolean> {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        return DisplayFacts.decoderFacts(
            infos.map { info ->
                val types = info.supportedTypes.toList()
                val capabilities = types.associateWith { type -> runCatching { info.getCapabilitiesForType(type) }.getOrNull() }
                DisplayFacts.Decoder(
                    profiles = types.associateWith { type -> capabilities[type]?.profileLevels?.map { it.profile }.orEmpty() },
                    hardware = DisplayFacts.hardware(info.name, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated else null),
                    uhdTypes = types.filter { type ->
                        runCatching { capabilities[type]?.videoCapabilities?.isSizeSupported(UHD_SIZE.first, UHD_SIZE.second) == true }.getOrDefault(false)
                    }.toSet(),
                )
            },
        )
    }

    private val UHD_SIZE = DisplayFacts.UHD_WIDTH to 2160
}
