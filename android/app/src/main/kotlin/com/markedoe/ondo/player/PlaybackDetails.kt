package com.markedoe.ondo.player

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import java.util.Locale

/**
 * The lines that say what the device was decoding and how it stood (formats, colours, decoders, memory,
 * temperature), for the record kept while a video plays (`PlayingNow`) and the problem report. English, like the
 * rest of the report; never an address or a title.
 */
@OptIn(UnstableApi::class)
internal object PlaybackDetails {
    private const val MEGABYTE = 1024 * 1024

    /** Codecs that name a Dolby Vision layer ("dvh1.05.06", in a list of codecs or alone). */
    private val DOLBY_VISION_CODECS = listOf("dvh1", "dvhe", "dva1", "dvav", "dav1")

    /** The thermal states of `PowerManager.getCurrentThermalStatus`, in order (0 to 6). */
    private val THERMAL_STATES = listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")

    /** "Video: video/hevc, hvc1.2.4.L153.B0, 3840×2160, 23.98 fps, 18.00 Mb/s". */
    fun video(format: Format): String = "Video: " + listOfNotNull(
        format.sampleMimeType,
        format.codecs,
        format.takeIf { it.width > 0 && it.height > 0 }?.let { "${it.width}×${it.height}" },
        format.frameRate.takeIf { it > 0 }?.let { String.format(Locale.ROOT, "%.2f fps", it) },
        format.bitrate.takeIf { it > 0 }?.let { PlaybackReporter.megabits(it.toLong()) },
    ).joinToString(", ")

    /** "Colour: HDR (PQ), BT.2020; Dolby Vision: yes": what asks the most of a TV's decoder and screen. */
    fun colour(format: Format): String {
        val info = format.colorInfo
        val transfer = when (val value = info?.colorTransfer ?: Format.NO_VALUE) {
            C.COLOR_TRANSFER_ST2084 -> "HDR (PQ)"
            C.COLOR_TRANSFER_HLG -> "HDR (HLG)"
            C.COLOR_TRANSFER_SDR -> "SDR"
            Format.NO_VALUE -> "transfer not given"
            else -> "transfer $value"
        }
        val space = when (val value = info?.colorSpace ?: Format.NO_VALUE) {
            C.COLOR_SPACE_BT2020 -> "BT.2020"
            C.COLOR_SPACE_BT709 -> "BT.709"
            C.COLOR_SPACE_BT601 -> "BT.601"
            Format.NO_VALUE -> "colour space not given"
            else -> "colour space $value"
        }
        return "Colour: $transfer, $space; Dolby Vision: ${if (isDolbyVision(format)) "yes" else "no"}"
    }

    fun isDolbyVision(format: Format): Boolean =
        format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION ||
            format.codecs.orEmpty().split(',').any { codec -> codec.trim().take(4).lowercase(Locale.ROOT) in DOLBY_VISION_CODECS }

    /** "Audio: audio/eac3, ec-3, 6 channels, 48000 Hz". */
    fun audio(format: Format): String = "Audio: " + listOfNotNull(
        format.sampleMimeType,
        format.codecs,
        format.channelCount.takeIf { it > 0 }?.let { "$it channels" },
        format.sampleRate.takeIf { it > 0 }?.let { "$it Hz" },
    ).joinToString(", ")

    /** "Decoders: video c2.vendor.hevc.decoder, audio c2.android.aac.decoder", once Media3 has started them. */
    fun decoders(video: String?, audio: String?): String? {
        val names = listOfNotNull(video?.let { "video $it" }, audio?.let { "audio $it" })
        return if (names.isEmpty()) null else "Decoders: " + names.joinToString(", ")
    }

    fun thermalStatus(status: Int): String = THERMAL_STATES.getOrNull(status) ?: "unknown ($status)"

    /** The device's thermal status (`PowerManager.getCurrentThermalStatus`, 0 to 6), if it gives one. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun thermalStatus(context: Context): Int? =
        runCatching { context.getSystemService(PowerManager::class.java)?.currentThermalStatus }.getOrNull()

    /** The app's memory, the device's, and its temperature: safe off the main thread. */
    fun system(context: Context): List<String> {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        val memory = runCatching {
            context.getSystemService(ActivityManager::class.java)?.let { activities ->
                ActivityManager.MemoryInfo().also(activities::getMemoryInfo)
            }
        }.getOrNull()
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) thermalStatus(context) else null
        return listOfNotNull(
            "App memory: ${used / MEGABYTE} of ${runtime.maxMemory() / MEGABYTE} MB used",
            memory?.let {
                "Device memory: ${it.availMem / MEGABYTE} of ${it.totalMem / MEGABYTE} MB available, " +
                    "low memory ${if (it.lowMemory) "yes" else "no"} (below ${it.threshold / MEGABYTE} MB)"
            },
            thermal?.let { "Thermal status: ${thermalStatus(it)}" },
        )
    }
}
