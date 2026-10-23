package com.markedoe.ondo.core.fuzz

import com.markedoe.ondo.core.catchup.CatchupUrlBuilder
import kotlin.test.assertEquals
import org.junit.Test

/** Catch-up templates (`catchup-source`) come from the playlist and are filled when a replay starts. */
class CatchupFuzzTest {
    private val long = 1_000_000

    @Test(timeout = 20_000)
    fun catchupTemplatesStayLinear() {
        for (unit in listOf("{utc:", "{a:", "\${start:", "{Y")) {
            CatchupUrlBuilder.fill("http://example.com/" + unit.repeat(long / unit.length), 0, 3_600_000, 7_200_000)
        }
        assertEquals(
            "http://example.com/x?s=1700000000&d=60&t=2023-11-14",
            CatchupUrlBuilder.fill("http://example.com/x?s={utc}&d={duration:60}&t={utc:Y-m-d}", 1_700_000_000_000, 1_700_003_600_000, 1_700_007_200_000),
        )
    }

    /** Templates fill as they did when the whole template was searched. */
    @Test(timeout = 30_000)
    fun catchupTemplatesFillAsBefore() {
        val tokenRegex = Regex("""\$?\{([a-zA-Z_]+)(?::([^}]*))?\}""")
        fun former(template: String, start: Long, end: Long, now: Long): String {
            val startTs = start / 1000
            val endTs = end / 1000
            val nowTs = now / 1000
            val duration = maxOf(endTs - startTs, 0)
            val offset = maxOf(nowTs - startTs, 0)
            return tokenRegex.replace(template) { match ->
                val name = match.groupValues[1]
                val argument = match.groups[2]?.value
                val divider = argument?.toIntOrNull()?.let { maxOf(it, 1) }
                val pattern = if (argument != null && divider == null) argument else null
                when (name) {
                    "utc", "start", "timestamp_start" -> pattern?.let { CatchupUrlBuilder.format(start, it) } ?: startTs.toString()
                    "utcend", "end" -> pattern?.let { CatchupUrlBuilder.format(end, it) } ?: endTs.toString()
                    "lutc", "now", "timestamp", "current" -> pattern?.let { CatchupUrlBuilder.format(now, it) } ?: nowTs.toString()
                    "duration" -> (duration / (divider ?: 1)).toString()
                    "offset" -> (offset / (divider ?: 1)).toString()
                    "Y", "m", "d", "H", "M", "S" -> CatchupUrlBuilder.format(start, name)
                    else -> match.value
                }
            }
        }
        val fuzz = Fuzz(seed = 13)
        val pieces = listOf("{", "}", "$", ":", "utc", "end", "now", "duration", "offset", "Y", "m", "-", "60", "0", "x", "?", "&", "/", "é")
        repeat(20_000) { case ->
            val template = buildString { repeat(1 + fuzz.int(16)) { append(fuzz.pick(pieces)) } }
            val start = fuzz.random.nextLong() / 1000
            val end = start + fuzz.int(10_000_000)
            assertEquals(former(template, start, end, end + 1), CatchupUrlBuilder.fill(template, start, end, end + 1), "case $case: ${describe(template)}")
        }
    }
}
