package com.markedoe.ondo.core.epg

import com.markedoe.ondo.core.TextNormalizer
import com.markedoe.ondo.core.model.EpgChannel
import com.markedoe.ondo.core.model.EpgData
import com.markedoe.ondo.core.model.EpgProgram
import com.markedoe.ondo.core.model.TimeWindow
import com.markedoe.ondo.core.net.Gzip
import com.markedoe.ondo.core.net.Urls
import com.markedoe.ondo.core.parsing.Sax
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/** The guide could not be read at all. */
class XmltvParseException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Streaming XMLTV parser (SAX). Gzip input is detected and inflated on the fly. Programmes outside the
 * filter's window or channels are dropped while parsing, so huge guides stay cheap in memory. A guide that
 * breaks half-way keeps what was read before the error.
 */
class XmltvParser {
    /**
     * Programmes of the guide, sorted by start within each channel.
     * @param window keep programmes overlapping it (`null` = everything)
     * @param channels guide channel ids to keep, case-insensitive (`null` = every channel)
     * @param timeShiftMillis added to every programme time (`tvg-shift` style correction)
     */
    fun parse(
        input: InputStream,
        window: TimeWindow? = null,
        channels: Set<String>? = null,
        timeShiftMillis: Long = 0,
    ): List<EpgProgram> = parseGuide(input, EpgFilter(window = window, channelIds = channels), timeShiftMillis).allPrograms

    /** Channels and programmes (by channel, sorted by start) of a guide read from a stream. */
    fun parseGuide(input: InputStream, filter: EpgFilter = EpgFilter.NONE, timeShiftMillis: Long = 0): EpgData {
        val handler = XmltvHandler(filter, timeShiftMillis)
        try {
            Sax.parse(Gzip.decompressingStream(input), handler)
        } catch (e: SAXException) {
            if (handler.isEmpty) throw XmltvParseException("Invalid XMLTV guide: ${e.message}", e)
        } catch (e: IOException) {
            if (handler.isEmpty) throw XmltvParseException("Invalid XMLTV guide: ${e.message}", e)
        }
        return handler.result()
    }

    /** Same as the stream variant, for a downloaded guide (gzip or plain). */
    fun parseGuide(bytes: ByteArray, filter: EpgFilter = EpgFilter.NONE, timeShiftMillis: Long = 0): EpgData =
        parseGuide(ByteArrayInputStream(Gzip.gunzipIfNeeded(bytes)), filter, timeShiftMillis)

    companion object {
        /**
         * Parses `20240101203000 +0100`, `20240101203000+0100`, `20240101203000` (UTC), `202401012030`
         * into epoch millis.
         */
        fun parseDate(text: String): Long? {
            var index = 0
            fun digits(count: Int): Int? {
                if (index + count > text.length) return null
                var value = 0
                for (i in index until index + count) {
                    val c = text[i]
                    if (c !in '0'..'9') return null
                    value = value * 10 + (c - '0')
                }
                index += count
                return value
            }
            val year = digits(4) ?: return null
            val month = digits(2) ?: return null
            val day = digits(2) ?: return null
            val hour = digits(2) ?: 0
            val minute = digits(2) ?: 0
            val second = digits(2) ?: 0

            // Optional timezone offset.
            while (index < text.length && text[index] == ' ') index++
            var offsetSeconds = 0
            if (index < text.length && (text[index] == '+' || text[index] == '-')) {
                val sign = if (text[index] == '-') -1 else 1
                index++
                val hh = digits(2)
                if (hh != null) {
                    if (index < text.length && text[index] == ':') index++ // "+01:00"
                    val mm = digits(2) ?: 0
                    offsetSeconds = sign * (hh * 3600 + mm * 60)
                }
            }

            if (month !in 1..12 || day !in 1..31) return null
            val days = daysFromCivil(year, month, day)
            val seconds = days * 86_400L + hour * 3600L + minute * 60L + second - offsetSeconds
            return seconds * 1000
        }

        /** Days since 1970-01-01 (Howard Hinnant's algorithm) — avoids slow date formatting on millions of rows. */
        internal fun daysFromCivil(year: Int, month: Int, day: Int): Long {
            val y = (if (month <= 2) year - 1 else year).toLong()
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val mp = (month + 9) % 12
            val doy = (153 * mp + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146_097 + doe - 719_468
        }

        /** xmltv_ns `"1.4.0/1"` (zero based) -> `"S02E05"`; on-screen values are kept as-is. */
        internal fun formatEpisode(value: String): String {
            val parts = value.split('.')
            if (parts.size < 2) return value
            fun number(part: String): Int? = part.split('/').firstOrNull { it.isNotEmpty() }?.trim()?.toIntOrNull()
            val season = number(parts[0]) ?: return value
            val episode = number(parts[1]) ?: return value
            return String.format(Locale.ROOT, "S%02dE%02d", season + 1, episode + 1)
        }
    }
}

/** Mutable programme while its child elements are read. */
private class ProgrammeBuilder(val channelId: String, val start: Long, val end: Long) {
    var title = ""
    var subtitle: String? = null
    var description: String? = null
    var category: String? = null
    var iconUrl: String? = null
    var episode: String? = null
    var rating: String? = null

    fun build() = EpgProgram(
        channelId = channelId,
        start = start,
        end = end,
        title = title.ifEmpty { "—" },
        description = description,
        subtitle = subtitle,
        category = category,
        iconUrl = iconUrl,
        episode = episode,
        rating = rating,
    )

    /** Stores the text of a programme child element (the first value of each kind wins). */
    fun fill(element: String, value: String) {
        when (element) {
            "title" -> if (title.isEmpty()) title = value
            "sub-title" -> if (subtitle == null) subtitle = value
            "desc" -> if (description == null) description = value
            "category" -> if (category == null) category = value
            "episode-num" -> if (episode == null) episode = XmltvParser.formatEpisode(value)
            "value" -> if (rating == null) rating = value
        }
    }
}

private class XmltvHandler(private val filter: EpgFilter, private val timeShift: Long) : DefaultHandler() {
    private val channels = LinkedHashMap<String, EpgChannel>()
    private val programs = LinkedHashMap<String, MutableList<EpgProgram>>()
    private var programCount = 0

    // Channel being parsed.
    private var channelId: String? = null
    private var channelNames = ArrayList<String>()
    private var channelIcon: String? = null

    // Programme being parsed.
    private var programme: ProgrammeBuilder? = null
    private var keepProgramme = false

    private val text = StringBuilder()
    private var capturing = false

    /** Lowercased guide channel id -> keep? */
    private val decisionCache = HashMap<String, Boolean>()

    val isEmpty: Boolean
        get() = programCount == 0 && channels.isEmpty()

    fun result(): EpgData {
        val sorted = LinkedHashMap<String, List<EpgProgram>>(programs.size * 2)
        for ((id, list) in programs) sorted[id] = list.sortedBy { it.start }
        return EpgData(channels = channels, programs = sorted)
    }

    private fun shouldKeep(id: String): Boolean {
        if (!filter.filtersChannels) return true
        val key = id.lowercase()
        decisionCache[key]?.let { return it }
        var keep = filter.channelIds?.contains(key) ?: false
        val nameKeys = filter.channelNameKeys
        if (!keep && nameKeys != null) {
            keep = if (TextNormalizer.channelMatchKey(id) in nameKeys) {
                true
            } else {
                channels[id]?.displayNames?.any { TextNormalizer.channelMatchKey(it) in nameKeys } ?: false
            }
        }
        decisionCache[key] = keep
        return keep
    }

    override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
        when (qName) {
            "programme" -> {
                val channel = attributes.getValue("channel")
                val start = attributes.getValue("start")?.let(XmltvParser::parseDate)
                if (channel == null || start == null) {
                    keepProgramme = false
                    return
                }
                var stop = attributes.getValue("stop")?.let(XmltvParser::parseDate) ?: (start + 3_600_000)
                if (stop <= start) stop = start + 60_000
                val shiftedStart = start + timeShift
                val shiftedStop = stop + timeShift
                val window = filter.window
                if (window != null && (shiftedStop < window.start || shiftedStart > window.end)) {
                    keepProgramme = false
                    return
                }
                keepProgramme = shouldKeep(channel)
                if (keepProgramme) programme = ProgrammeBuilder(channel, shiftedStart, shiftedStop)
            }

            "channel" -> {
                channelId = attributes.getValue("id")
                channelNames = ArrayList()
                channelIcon = null
            }

            "icon" -> {
                val url = Urls.lenient(attributes.getValue("src"))
                val current = programme
                if (current != null && keepProgramme) {
                    if (current.iconUrl == null) current.iconUrl = url
                } else if (channelId != null) {
                    channelIcon = channelIcon ?: url
                }
            }

            "title", "sub-title", "desc", "category", "episode-num", "display-name", "value" -> {
                if ((programme != null && keepProgramme) || (channelId != null && qName == "display-name")) {
                    capturing = true
                    text.setLength(0)
                }
            }
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (capturing) text.appendRange(ch, start, start + length)
    }

    override fun endElement(uri: String?, localName: String?, qName: String) {
        when (qName) {
            "programme" -> {
                val current = programme
                if (keepProgramme && current != null) {
                    programs.getOrPut(current.channelId) { ArrayList() }.add(current.build())
                    programCount++
                }
                programme = null
                keepProgramme = false
            }

            "channel" -> {
                channelId?.let { channels[it] = EpgChannel(it, channelNames, channelIcon) }
                channelId = null
            }

            else -> {
                if (!capturing) return
                capturing = false
                val value = text.toString().trim()
                text.setLength(0)
                if (value.isEmpty()) return
                val current = programme
                if (current != null) {
                    current.fill(qName, value)
                } else if (qName == "display-name") {
                    channelNames.add(value)
                }
            }
        }
    }
}
