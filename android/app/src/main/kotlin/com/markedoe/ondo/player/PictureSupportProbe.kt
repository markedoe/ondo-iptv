package com.markedoe.ondo.player

import android.app.Activity
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioCapabilities
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
    /** How many sizes the display lists (the mode of the moment's among them): 1 for a box in "auto" listing its interface's. */
    val modeSizes: Int = 0,
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
    /** What the sound output takes ([AudioOutput]); null until read, off the main thread. */
    val audioOutput: AudioOutput? = null,
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

        /**
         * A TV whose display takes HDR, with a hardware decoder for 4K ([uhdDecoder]), that lists one size only and whose
         * display size is not read (a box in "auto").
         */
        HDR_AND_DECODER,
    }

    /**
     * Whether Media3 read a display size of its own ([displaySizeWidth]): one that is not the mode of the moment's, which
     * is what it gives back when a TV writes no `display-size` property (it does not say which it was).
     */
    val displaySizeRead: Boolean
        get() = displaySizeWidth > 0 && displaySizeHeight > 0 && (displaySizeWidth != currentWidth || displaySizeHeight != currentHeight)

    /**
     * Why this is a 4K screen; null when nothing says so. In this order: the modes (physical sizes) the display lists;
     * else the size Media3 reads of a TV (a box in "auto" may list its 1080p interface mode only, while its
     * `display-size` property says 3840x2160); else, on a TV only, a display that takes HDR (HDR10, HDR10+, HLG, Dolby
     * Vision) with a hardware decoder for 3840×2160, only where nothing else can say: one size listed (the box in
     * "auto") and no display size read. A display size read below 4K is no 4K; a display listing several sizes, all
     * below 4K (a 1080p HDR stick or TV, whose decoder may still take 2160p), is no 4K. Never a phone or a tablet on that
     * last rule, never a software decoder. A 1080p HDR TV behind a 4K box in "auto" reads as 4K: its 4K versions still
     * play, downscaled.
     */
    val uhdSource: UhdSource?
        get() = when {
            maxOf(largestWidth, largestHeight, currentWidth, currentHeight) >= UHD_WIDTH -> UhdSource.MODES
            maxOf(displaySizeWidth, displaySizeHeight) >= UHD_WIDTH -> UhdSource.DISPLAY_SIZE
            hdrAndDecoderApplies && uhdDecoder == true -> UhdSource.HDR_AND_DECODER
            else -> null
        }

    /** Whether the HDR-and-decoder rule may say 4K here, the decoders aside. */
    private val hdrAndDecoderApplies: Boolean
        get() = television == true && modeSizes == 1 && !displaySizeRead && (hdr10 || hdr10Plus || hlg || dolbyVisionDisplay)

    /** Whether what decides 4K is read ([television], [uhdDecoder] where the last rule needs it), so a 4K not found is a "no". */
    val uhdChecked: Boolean get() = uhdSource != null || (television != null && (!hdrAndDecoderApplies || uhdDecoder != null))

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

    /** These facts with the decoders ([found]). */
    fun with(found: Decoders): DisplayFacts = copy(
        dolbyVisionDecoder = found.dolbyVisionDecoder,
        hevcMain10Decoder = found.hevcMain10Decoder,
        uhdDecoder = found.uhdDecoder,
    )

    /** These facts with what was read of the screen in the background ([found]). */
    fun with(found: Screen): DisplayFacts = copy(
        displaySizeWidth = found.displaySize.first,
        displaySizeHeight = found.displaySize.second,
        television = found.television,
        audioOutput = found.audioOutput ?: audioOutput,
    )

    /** The decoders, read once per process, a while after launch. */
    data class Decoders(val dolbyVisionDecoder: Boolean, val hevcMain10Decoder: Boolean, val uhdDecoder: Boolean)

    /** What is read of the screen at once, in the background, each time an activity reads it: cheap system reads. */
    data class Screen(val displaySize: Pair<Int, Int>, val television: Boolean, val audioOutput: AudioOutput? = null)

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
                modeSizes = (modes + current).filter { it.first > 0 && it.second > 0 }.distinct().size,
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

/**
 * What the sound output takes, as Media3 reads it (`AudioCapabilities`: the HDMI or default route of the moment): the
 * surround encodings it takes as they are, for the TV or amplifier to decode ([passthrough], in the order of
 * [ENCODINGS]), and its channels ([maxChannels], [channels]: 2 for stereo, 6 for 5.1, 8 for 7.1).
 */
@OptIn(UnstableApi::class)
data class AudioOutput(val passthrough: List<String>, val maxChannels: Int) {
    companion object {
        /** The encodings the panel names, as it names them. */
        val ENCODINGS: List<Pair<Int, String>> = listOf(
            C.ENCODING_AC3 to "AC-3",
            C.ENCODING_E_AC3 to "E-AC-3",
            C.ENCODING_E_AC3_JOC to "Atmos (E-AC-3 JOC)",
            C.ENCODING_DOLBY_TRUEHD to "TrueHD",
            C.ENCODING_DTS to "DTS",
            C.ENCODING_DTS_HD to "DTS-HD",
            C.ENCODING_DTS_UHD_P2 to "DTS:X",
        )

        /** The output of [supports] (an encoding taken as it is) and [maxChannels]. */
        fun of(supports: (Int) -> Boolean, maxChannels: Int): AudioOutput =
            AudioOutput(ENCODINGS.filter { supports(it.first) }.map { it.second }, maxChannels)

        /** Media3's default channel count, when nothing said one (its `DEFAULT_MAX_CHANNEL_COUNT`): unknown, not 10 speakers. */
        private const val MEDIA3_DEFAULT_CHANNELS = 10

        /**
         * The output's channels: the most of Media3's speaker layouts ([speakerLayouts], channel masks in no set order:
         * ARC and eARC lists vary; a plain HDMI output reads stereo there), or its [maxChannelCount] (what the HDMI plug
         * says) on an HDMI output ([hdmi]) or wherever it says more; never Media3's default of 10; 2 when nothing says.
         */
        fun channels(speakerLayouts: List<Int>, maxChannelCount: Int, hdmi: Boolean): Int {
            val layout = speakerLayouts.maxOfOrNull(Integer::bitCount) ?: 0
            val said = maxChannelCount.takeIf { it in 1 until MEDIA3_DEFAULT_CHANNELS } ?: 0
            val channels = if (hdmi && said > 0) said else maxOf(layout, said)
            return channels.takeIf { it > 0 } ?: 2
        }

        private val HDMI_TYPES = setOf(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC)

        /** The output of the moment, for a film's sound; off the main thread (it asks the system); null when it cannot be read. */
        @OptIn(UnstableApi::class)
        fun read(context: Context): AudioOutput? = runCatching {
            val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()
            val capabilities = AudioCapabilities.getCapabilities(context.applicationContext, attributes, null, emptyList())
            val audio = context.applicationContext.getSystemService(AudioManager::class.java)
            val hdmi = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty().any { it.type in HDMI_TYPES }
            of(capabilities::supportsEncoding, channels(capabilities.speakerLayoutChannelMasks, capabilities.maxChannelCount, hdmi))
        }.getOrNull()
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
    @Volatile private var decoders: DisplayFacts.Decoders? = null

    /** What was read of the screen in the background last ([readScreen]); read again each time an activity reads it. */
    @Volatile private var screen: DisplayFacts.Screen? = null

    /** The background reads run at the lowest priority: never in the way of the launch, the first Home or a stream. */
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
     * The facts of [activity]'s display (cheap: no decoder list and no system property on the main thread). In the
     * background, at once, the display's size as Media3 reads it, whether this is a TV and the sound output (each time:
     * a box in "auto" changes mode, an amplifier is switched on); the decoders once, a while later. [onDecoders] gets the
     * facts after each.
     */
    fun of(activity: Activity, onDecoders: (DisplayFacts) -> Unit = {}): DisplayFacts {
        val read = runCatching { read(activity) }.getOrDefault(DisplayFacts())
        val known = decoders
        val facts = merged(read, screen, known)
        this.facts = facts
        val context = activity.applicationContext
        val display = runCatching { display(activity) }.getOrNull()
        background.execute {
            val found = runCatching { readScreen(context, display) }.getOrNull() ?: return@execute
            screen = found
            val updated = (this.facts ?: facts).with(found)
            this.facts = updated
            onDecoders(updated)
        }
        if (known == null) {
            background.execute {
                // A short wait first: the first Home is drawn before the decoder list is read.
                runCatching { Thread.sleep(DECODERS_DELAY_MS) }
                if (decoders != null) return@execute
                val (dolbyVision, main10, uhd) = runCatching { listDecoders() }.getOrNull() ?: return@execute
                val found = DisplayFacts.Decoders(dolbyVision, main10, uhd)
                decoders = found
                val withDecoders = (this.facts ?: facts).with(found)
                this.facts = withDecoders
                onDecoders(withDecoders)
            }
        }
        return facts
    }

    private fun merged(read: DisplayFacts, screen: DisplayFacts.Screen?, decoders: DisplayFacts.Decoders?): DisplayFacts {
        var facts = read
        if (screen != null) facts = facts.with(screen)
        if (decoders != null) facts = facts.with(decoders)
        return facts
    }

    @Suppress("DEPRECATION")
    private fun display(activity: Activity): Display =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else null) ?: activity.windowManager.defaultDisplay

    @Suppress("DEPRECATION")
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
     * Off the main thread, cheap: the display's size as Media3 reads it (its public `Util.getCurrentDisplayModeSize`, the
     * one its track selector uses: on a TV the `display-size` property a box writes; Media3 reads it, the app looks up
     * nothing by name), whether this is a TV, the sound output.
     */
    @OptIn(UnstableApi::class)
    private fun readScreen(context: Context, display: Display?): DisplayFacts.Screen {
        val television = runCatching { Util.isTv(context) }.getOrDefault(false)
        val size = display?.let { runCatching { Util.getCurrentDisplayModeSize(context, it) }.getOrNull() }
        return DisplayFacts.Screen(
            displaySize = (size?.x ?: 0) to (size?.y ?: 0),
            television = television,
            audioOutput = AudioOutput.read(context),
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
