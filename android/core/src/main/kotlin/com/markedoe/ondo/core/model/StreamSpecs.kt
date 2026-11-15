package com.markedoe.ondo.core.model

import com.markedoe.ondo.core.library.VersionTag
import kotlinx.serialization.Serializable

/** Where a stream's specs come from, best first (docs/VERSIONS.md, "What a version is made of"). */
@Serializable
enum class SpecSource {
    /** The player read them from the stream's tracks while it played. */
    MEASURED,

    /** The provider's `get_vod_info` (`info.video`, `info.audio`, `info.bitrate`: what its server's ffprobe read). */
    PROVIDER,
}

/** The picture's dynamic range. [SDR] is known too: a measured SDR stream is no HDR stream, whatever its name says. */
@Serializable
enum class PictureRange { SDR, HDR10, HDR10_PLUS, HLG, DOLBY_VISION }

/**
 * What a stream is made of: its picture's size, range and codec, its sound's codec and channels. Each field is null (or
 * false) when unknown: nothing is guessed. Measured by the player ([SpecSource.MEASURED]) or given by the provider
 * ([SpecSource.PROVIDER]); the tags of the name (`FilmVersions.labelTags`) come last. The same rules and words as
 * Apple's `StreamSpecs` (IPTVCore); the shared tables are `fixtures/stream-specs/specs.json` and
 * `fixtures/film-versions/labels.json`.
 */
@Serializable
data class StreamSpecs(
    val width: Int? = null,
    val height: Int? = null,
    val range: PictureRange? = null,
    /** "H.264", "HEVC", "AV1"… ([videoCodec]). */
    val videoCodec: String? = null,
    /** "AAC", "AC-3", "E-AC-3", "DTS", "TrueHD"… ([audioCodec]). */
    val audioCodec: String? = null,
    val channels: Int? = null,
    /** The provider's layout of them ("5.1", "4.1", "3.0": [layoutOf]); null when it gives none. */
    val channelLayout: String? = null,
    /** Dolby Atmos: E-AC-3 JOC, or a provider's profile naming Atmos. */
    val atmos: Boolean = false,
    /** The provider's whole bitrate, in kbit/s. */
    val bitrate: Int? = null,
    val source: SpecSource = SpecSource.PROVIDER,
) {
    /** Whether anything at all is known. */
    val isEmpty: Boolean
        get() = resolution == null && range == null && videoCodec == null && audioCodec == null && channels == null &&
            channelLayout == null && !atmos

    /** The sound's layout: the provider's ("4.1"), else its channels' ([layout]: "5.1" for six); null when unknown. */
    val soundLayout: String? get() = channelLayout ?: channels?.let(::layout)

    /** "SD", "720p", "1080p", "4K" from the picture's size; null when unknown ([resolutionOf]). */
    val resolution: String? get() = resolutionOf(width, height)

    /** "Dolby Vision", "HDR10+", "HDR10", "HLG"; null for SDR or unknown. */
    val rangeLabel: String? get() = rangeLabel(range)

    /** The sound as the facts line shows it: "Atmos", "5.1 AAC", "2.0 AC-3", "AAC"; null when unknown. */
    val audioLabel: String?
        get() {
            if (atmos) return ATMOS
            return listOfNotNull(soundLayout, audioCodec).joinToString(" ").ifEmpty { null }
        }

    /** The sound as a chip says it: "Atmos", "5.1", else its codec; null when unknown. */
    val chipAudio: String? get() = if (atmos) ATMOS else soundLayout ?: audioCodec

    /**
     * These specs where they are known, [other]'s where they are not; this one's source. Atmos stays when either says it:
     * a player that reads no Atmos (AVFoundation, Media3 on TrueHD) does not take the provider's away. The provider's
     * layout only where it agrees with the channels known.
     */
    fun orElse(other: StreamSpecs?): StreamSpecs {
        if (other == null) return this
        return copy(
            width = width ?: other.width,
            height = height ?: other.height,
            range = range ?: other.range,
            videoCodec = videoCodec ?: other.videoCodec,
            audioCodec = audioCodec ?: other.audioCodec,
            channels = channels ?: other.channels,
            channelLayout = channelLayout ?: other.channelLayout?.takeIf { channels == null || channelsOfLayout(it) == channels },
            atmos = atmos || other.atmos,
            bitrate = bitrate ?: other.bitrate,
        )
    }

    /** "1920×802", "HEVC", "Dolby Vision", "5.1 E-AC-3", "Atmos", "8 450 kbit/s": every known field, for the title data panel. */
    fun summary(): String = listOfNotNull(
        if (width != null && height != null) "$width×$height" else height?.let { "${it}p" },
        resolution,
        range?.let { if (it == PictureRange.SDR) "SDR" else rangeLabel(it) },
        videoCodec,
        listOfNotNull(soundLayout, audioCodec).joinToString(" ").ifEmpty { null },
        ATMOS.takeIf { atmos },
        bitrate?.let { "$it kbit/s" },
    ).joinToString(" · ").ifEmpty { "–" }

    /**
     * The block of the provider's ffprobe answer (`info.video` or `info.audio`): its scalar fields by name, in lower case
     * ([fields], numbers as text), and the types of its `side_data_list` in lower case ([sideData]: "dovi configuration
     * record", "hdr dynamic metadata smpte2094-40 (hdr10+)"). Free text (a stream's title) is never read.
     */
    data class ProbeBlock(val fields: Map<String, String>, val sideData: List<String> = emptyList())

    /** The words a screen reader says for specs, in the viewer's language (the app's strings; English by default). */
    data class Words(
        /** "%1$s surround sound" (`%1$s` is "5.1"). */
        val surround: String = "%1\$s surround sound",
        val stereo: String = "Stereo sound",
        val mono: String = "Mono sound",
        val atmos: String = "Dolby Atmos sound",
    )

    companion object {
        const val ATMOS = "Atmos"

        /**
         * The class of a picture's size: 4K, 1080p, 720p or SD. A film in scope is wide and short (1920×802 is 1080p,
         * 3840×1600 4K), an anamorphic one narrow and tall (1440×1080 is 1080p): either side reaching a class's is enough.
         * Null when neither is known (zero is unknown).
         */
        fun resolutionOf(width: Int?, height: Int?): String? {
            val w = width?.takeIf { it > 0 } ?: 0
            val h = height?.takeIf { it > 0 } ?: 0
            return when {
                w == 0 && h == 0 -> null
                w >= 3200 || h >= 1800 -> "4K"
                w >= 1700 || h >= 900 -> "1080p"
                w >= 1200 || h >= 650 -> "720p"
                else -> "SD"
            }
        }

        fun rangeLabel(range: PictureRange?): String? = when (range) {
            PictureRange.DOLBY_VISION -> "Dolby Vision"
            PictureRange.HDR10_PLUS -> "HDR10+"
            PictureRange.HDR10 -> "HDR10"
            PictureRange.HLG -> "HLG"
            PictureRange.SDR, null -> null
        }

        /** "5.1" for six channels, "2.0" for two, "7.1" for eight; "N ch" past them. */
        fun layout(channels: Int): String = when (channels) {
            1 -> "1.0"
            2 -> "2.0"
            3 -> "3.0"
            4 -> "4.0"
            5 -> "5.0"
            6 -> "5.1"
            7 -> "6.1"
            8 -> "7.1"
            else -> "$channels ch"
        }

        /** A codec's conventional name from ffprobe's `codec_name`, a FourCC or a MIME type's subtype; null when unknown. */
        fun videoCodec(raw: String?): String? {
            val name = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return VIDEO_CODECS[name]
        }

        /** The same for sound ("aac", "ac3", "eac3", "mp4a", "ec-3"…). */
        fun audioCodec(raw: String?): String? {
            val name = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            if (name.startsWith("pcm_") || name.startsWith("lpcm")) return "PCM"
            return AUDIO_CODECS[name]
        }

        private val VIDEO_CODECS: Map<String, String> = mapOf(
            "h264" to "H.264", "avc" to "H.264", "avc1" to "H.264", "avc3" to "H.264", "x264" to "H.264",
            "hevc" to "HEVC", "h265" to "HEVC", "hvc1" to "HEVC", "hev1" to "HEVC", "x265" to "HEVC",
            "dvh1" to "HEVC", "dvhe" to "HEVC",
            "av1" to "AV1", "av01" to "AV1", "dav1" to "AV1",
            "vp9" to "VP9", "vp09" to "VP9", "x-vnd.on2.vp9" to "VP9", "vp8" to "VP8", "x-vnd.on2.vp8" to "VP8",
            "mpeg2video" to "MPEG-2", "mpeg2" to "MPEG-2", "mp2v" to "MPEG-2",
            "mpeg4" to "MPEG-4", "mp4v" to "MPEG-4", "mp4v-es" to "MPEG-4", "xvid" to "MPEG-4", "divx" to "MPEG-4",
            "vc1" to "VC-1", "wvc1" to "VC-1",
        )

        private val AUDIO_CODECS: Map<String, String> = mapOf(
            "aac" to "AAC", "mp4a" to "AAC", "aac_latm" to "AAC", "mp4a-latm" to "AAC",
            "ac3" to "AC-3", "ac-3" to "AC-3",
            "eac3" to "E-AC-3", "ec-3" to "E-AC-3", "ec3" to "E-AC-3", "eac3-joc" to "E-AC-3",
            "ac4" to "AC-4", "ac-4" to "AC-4",
            "dts" to "DTS", "dca" to "DTS", "vnd.dts" to "DTS", "dtsc" to "DTS",
            "vnd.dts.hd" to "DTS-HD", "dtsh" to "DTS-HD", "dtsl" to "DTS-HD",
            "truehd" to "TrueHD", "true-hd" to "TrueHD", "mlp" to "TrueHD", "mlpa" to "TrueHD",
            "mp3" to "MP3", "mpeg" to "MP3", ".mp3" to "MP3", "mp2" to "MP2", "mpeg-l2" to "MP2",
            "opus" to "Opus", "flac" to "FLAC", "vorbis" to "Vorbis", "alac" to "ALAC", "raw" to "PCM", "lpcm" to "PCM",
        )

        /** FourCCs of Dolby Vision pictures (a track's `codecs`, a sample description's type). */
        private val DOLBY_VISION_CODECS = setOf("dvh1", "dvhe", "dav1", "dvav", "dva1")

        /** A codec's first part ("hvc1.2.4.L153" is "hvc1"), in lower case. */
        private fun fourCC(codecs: String?): String? = codecs?.trim()?.substringBefore('.')?.lowercase()?.takeIf { it.isNotEmpty() }

        /** A video track's codec from its MIME type ("video/hevc") and `codecs` ("dvh1.05.06" under "video/dolby-vision"). */
        fun videoCodecOfMime(mime: String?, codecs: String?): String? {
            val subtype = mime?.substringAfter('/')?.trim()?.lowercase()
            if (subtype == "dolby-vision") return videoCodec(fourCC(codecs))
            return videoCodec(subtype) ?: videoCodec(fourCC(codecs))
        }

        /** Whether a video track is Dolby Vision, from its MIME type or its `codecs`. */
        fun isDolbyVision(mime: String?, codecs: String?): Boolean =
            mime?.trim()?.lowercase() == "video/dolby-vision" || fourCC(codecs) in DOLBY_VISION_CODECS

        /** An audio track's codec from its MIME type ("audio/eac3-joc" is E-AC-3, [isAtmosMime] says Atmos). */
        fun audioCodecOfMime(mime: String?, codecs: String?): String? =
            audioCodec(mime?.substringAfter('/')?.trim()?.lowercase()) ?: audioCodec(fourCC(codecs))

        /** E-AC-3 with Dolby Atmos (JOC), as the player names it. */
        fun isAtmosMime(mime: String?): Boolean = mime?.trim()?.lowercase() == "audio/eac3-joc"

        /**
         * The provider's layout as the apps say it: "5.1(side)" is "5.1", "4.1" and "3.0" stay, "stereo" is "2.0", "mono"
         * "1.0", "quad" "4.0"; null when it is none of those.
         */
        fun layoutOf(raw: String?): String? {
            val layout = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            when (layout) {
                "mono" -> return "1.0"
                "stereo", "downmix" -> return "2.0"
                "quad" -> return "4.0"
                "hexagonal" -> return "6.0"
                "octagonal" -> return "8.0"
            }
            val main = layout.takeWhile { it != '(' }.trim()
            if (main.count { it == '.' } != 1 || !main.all { it == '.' || it in '0'..'9' }) return null
            return main.takeIf { channelsOfLayout(it) != null }
        }

        /**
         * The sound the player measured: the track marked default ([defaults]), else the one of the most channels, the
         * first of them; -1 for none. Not the viewer's choice of the moment: a stream's sound is what it offers first.
         */
        fun measuredAudio(defaults: List<Boolean>, channels: List<Int?>): Int {
            val marked = defaults.indexOfFirst { it }
            if (marked >= 0) return marked
            var best = -1
            for (i in channels.indices) if (best < 0 || (channels[i] ?: 0) > (channels[best] ?: 0)) best = i
            return best
        }

        /** "5.1(side)" is 6, "stereo" 2, "7.1" 8, "mono" 1; null when it says no number. */
        fun channelsOfLayout(raw: String?): Int? {
            val layout = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            when (layout) {
                "mono" -> return 1
                "stereo", "downmix" -> return 2
                "quad" -> return 4
                "hexagonal" -> return 6
                "octagonal" -> return 8
            }
            // "5.1", "7.1(wide)", "6.1(back)": the digits before and after the dot.
            val main = layout.takeWhile { it != '(' }
            val dot = main.indexOf('.')
            if (dot <= 0) return null
            val front = main.substring(0, dot).toIntOrNull() ?: return null
            val low = main.substring(dot + 1).takeWhile { it in '0'..'9' }.toIntOrNull() ?: return null
            return (front + low).takeIf { it in 1..32 }
        }

        /** A whole number written as text ("1920", "1920.0"); null for anything else, zero or less. */
        internal fun positive(raw: String?): Int? {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            text.toIntOrNull()?.let { return it.takeIf { n -> n > 0 } }
            val number = text.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 1 && it < Int.MAX_VALUE } ?: return null
            return number.toInt()
        }

        /** Transfer functions of SDR pictures, as ffprobe names them (`color_transfer`). */
        private val SDR_TRANSFERS = setOf(
            "bt709", "bt470m", "bt470bg", "smpte170m", "smpte240m", "bt601", "iec61966-2-1", "iec61966-2-4", "gamma22",
            "gamma28", "linear", "bt1361e", "bt2020-10", "bt2020-12",
        )

        /**
         * Specs from the provider's ffprobe answer: [video] (`info.video`), [audio] (`info.audio`), [bitrate]
         * (`info.bitrate`, kbit/s). A field missing, empty, zero or unknown stays unknown; null when nothing is known.
         * Dolby Vision from its configuration record or its FourCC; HDR10+ only from SMPTE ST 2094 metadata named in the
         * block ("SMPTE2094-40"); HDR10 and HLG from `color_transfer` (never from a 10-bit profile alone).
         */
        fun fromProbe(video: ProbeBlock?, audio: ProbeBlock?, bitrate: String?): StreamSpecs? {
            val v = video?.fields.orEmpty()
            val a = audio?.fields.orEmpty()
            val tag = v["codec_tag_string"]?.lowercase()
            val range = when {
                video == null -> null
                tag in DOLBY_VISION_CODECS || "dolby vision" in v["profile"]?.lowercase().orEmpty() ||
                    video.sideData.any { "dovi" in it || "dolby vision" in it } -> PictureRange.DOLBY_VISION
                video.sideData.any { "smpte2094" in it || "hdr10+" in it } -> PictureRange.HDR10_PLUS
                else -> when (v["color_transfer"]?.trim()?.lowercase()) {
                    "smpte2084" -> PictureRange.HDR10
                    "arib-std-b67" -> PictureRange.HLG
                    in SDR_TRANSFERS -> PictureRange.SDR
                    else -> null
                }
            }
            val audioProfile = a["profile"]?.lowercase().orEmpty()
            val audioCodec = audioCodec(a["codec_name"]) ?: audioCodec(a["codec_tag_string"])
            val specs = StreamSpecs(
                width = positive(v["width"]) ?: positive(v["coded_width"]),
                height = positive(v["height"]) ?: positive(v["coded_height"]),
                range = range,
                videoCodec = videoCodec(v["codec_name"]) ?: videoCodec(tag),
                audioCodec = if (audioCodec == "DTS" && "dts-hd" in audioProfile) "DTS-HD" else audioCodec,
                channels = positive(a["channels"])?.takeIf { it <= 32 } ?: channelsOfLayout(a["channel_layout"]),
                channelLayout = layoutOf(a["channel_layout"]),
                atmos = "atmos" in audioProfile || "joc" in audioProfile,
                bitrate = positive(bitrate),
                source = SpecSource.PROVIDER,
            )
            return specs.takeUnless { it.isEmpty && it.bitrate == null }
        }

        /**
         * The facts line's badges of a version ("4K", "Dolby Vision", "HEVC", "5.1 AAC"), in that order: from its
         * [specs] where they know, else from the tags of its name ([nameTags]: "4K", "1080p", "HD", "SD", "Dolby Vision",
         * "HDR", "HEVC"); its sound only from its specs (a name rarely says it). Nothing unknown is shown.
         */
        fun badges(specs: StreamSpecs?, nameTags: List<VersionTag>): List<String> {
            val quality = specs?.resolution ?: when {
                VersionTag.UHD in nameTags -> "4K"
                VersionTag.FHD in nameTags -> "1080p"
                VersionTag.HD in nameTags -> "HD"
                VersionTag.SD in nameTags -> "SD"
                else -> null
            }
            val range = if (specs?.range != null) specs.rangeLabel else when {
                VersionTag.DOLBY_VISION in nameTags -> "Dolby Vision"
                VersionTag.HDR in nameTags -> "HDR"
                else -> null
            }
            val codec = specs?.videoCodec ?: "HEVC".takeIf { VersionTag.HEVC in nameTags }
            return listOfNotNull(quality, range, codec, specs?.audioLabel)
        }

        /** What a screen reader says of [badges]' specs: "4K, Dolby Vision, HEVC, 5.1 surround sound, AAC". */
        fun description(specs: StreamSpecs?, nameTags: List<VersionTag>, words: Words = Words()): String {
            val picture = badges(specs, nameTags).let { if (specs?.audioLabel != null) it.dropLast(1) else it }
            val sound = specs?.let { s ->
                val layout = s.soundLayout
                val heard = when {
                    s.atmos -> words.atmos
                    layout == null -> null
                    layout == "1.0" -> words.mono
                    layout == "2.0" -> words.stereo
                    else -> format(words.surround, layout)
                }
                listOfNotNull(heard, s.audioCodec)
            }.orEmpty()
            return (picture + sound).joinToString(", ")
        }

        private fun format(pattern: String, value: String): String =
            runCatching { String.format(java.util.Locale.ROOT, pattern, value) }.getOrElse { "$pattern $value" }
    }
}
