package com.markedoe.ondo.core.fuzz

import com.markedoe.ondo.core.parsing.TitleParser
import java.util.regex.Pattern
import kotlin.test.assertEquals
import org.junit.Test

/**
 * Every playlist title goes through [TitleParser] while loading, and again on the main thread (classification,
 * episode titles): it must stay linear in the length of a title, however broken the title is.
 */
class TitleParserFuzzTest {
    /** One megabyte, as in a broken playlist line. */
    private val long = 1_000_000

    @Test(timeout = 20_000)
    fun titleParserStaysLinearOnLongTitles() {
        for (run in listOf(" ", ".", "-", "_", "|", ":", "\t")) {
            val separators = run.repeat(long)
            assertEquals(null, TitleParser.parseEpisode("S1" + separators + "x"), describe("S1" + run))
            assertEquals(null, TitleParser.parseEpisode("a" + separators + "season"), describe("a season" + run))
            assertEquals(TitleParser.TitleAndYear("1999" + separators + "x", null), TitleParser.splitYear("1999" + separators + "x"))
        }
        // Each marker before a line break used to scan the rest of the title again.
        for (marker in listOf("S1E1 ", "1x1 ", "Season 1 Episode 1 ")) {
            TitleParser.parseEpisode(marker.repeat(long / marker.length) + "\u2028x")
            TitleParser.parseEpisode(marker.repeat(long / marker.length) + "\nx")
        }
        // Still found after a long run.
        val episode = TitleParser.parseEpisode("Show" + " ".repeat(long) + "S01E02 Pilot")
        assertEquals(TitleParser.EpisodeInfo("Show", 1, 2, "Pilot"), episode)
        assertEquals(TitleParser.TitleAndYear("Film", 2010), TitleParser.splitYear("Film" + " ".repeat(long) + "(2010)"))
    }

    /**
     * The linear patterns find exactly what the former ones found, except that a line break after the episode
     * marker no longer hides the episode (the former tail `(.*)$` stopped at it).
     */
    @Test(timeout = 60_000)
    fun titleParserMatchesItsFormerPatterns() {
        fun compile(tail: String) = listOf(
            """^(.*?)[\s._\-|:]*\bS(\d{1,2})[\s._\-]*E(\d{1,4})\b$tail""",
            """^(.*?)[\s._\-|:]*\b(\d{1,2})x(\d{1,3})\b$tail""",
            """^(.*?)[\s._\-|:]*\b(?:Saison|Season|Temporada|Staffel)\s*(\d{1,2})[\s._\-,]*""" +
                """(?:[EÉ]pisode|Episode|Ep\.?|Folge|Capitulo)\s*(\d{1,4})\b$tail""",
        ).map { Pattern.compile(it, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE) }
        val former = compile("(.*)$")
        val formerWithAnyTail = compile("(?s:(.*?))$")
        val formerYear = Pattern.compile("""^(.*?)[\s\-_.]*[(\[]?((?:19|20)\d{2})[)\]]?\s*$""")
        fun formerEpisode(title: String, patterns: List<Pattern>): TitleParser.EpisodeInfo? {
            if (!TitleParser.mayContainEpisodeMarker(title)) return null
            for (pattern in patterns) {
                val match = pattern.matcher(title)
                if (!match.find()) continue
                val season = match.group(2)?.toIntOrNull() ?: continue
                val episode = match.group(3)?.toIntOrNull() ?: continue
                val name = match.group(1).orEmpty().trim { it in " -–|:._([" }.trim()
                if (name.isEmpty()) continue
                val rest = match.group(4).orEmpty().trimStart { it in " -–|:._" }.trim()
                return TitleParser.EpisodeInfo(name, season, episode, rest.ifEmpty { "Episode $episode" })
            }
            return null
        }
        fun formerYear(title: String): TitleParser.TitleAndYear {
            if (title.length < 4 || title.none { it in '0'..'9' }) return TitleParser.TitleAndYear(title, null)
            val match = formerYear.matcher(title)
            if (!match.find()) return TitleParser.TitleAndYear(title, null)
            val year = match.group(2)?.toIntOrNull() ?: return TitleParser.TitleAndYear(title, null)
            val name = match.group(1).orEmpty().trim()
            return if (name.isEmpty()) TitleParser.TitleAndYear(title, year) else TitleParser.TitleAndYear(name, year)
        }
        val lineTerminators = "\n\r\u0085\u2028\u2029"
        fun hasInnerLineBreak(title: String): Boolean {
            val body = if (title.endsWith("\r\n")) title.dropLast(2) else title.dropLast(1)
            return body.any { it in lineTerminators }
        }
        val fuzz = Fuzz(seed = 7)
        var found = 0
        var withLineBreaks = 0
        repeat(CASES) { case ->
            val title = buildString {
                repeat(1 + fuzz.int(8)) {
                    append(
                        when (fuzz.int(6)) {
                            0 -> fuzz.pick(Fuzz.SEPARATORS + listOf("\n", "\r", "\r\n", "\u2028", "\u0085", "\u000B")).repeat(1 + fuzz.int(4))
                            1 -> fuzz.pick(Fuzz.NUMBERING)
                            2 -> fuzz.pick("(", ")", "[", "]") + fuzz.pick("1999", "2010", "2099", "1899", "20", "199")
                            3 -> fuzz.pick(Fuzz.UNICODE)
                            else -> fuzz.pick(Fuzz.WORDS)
                        },
                    )
                }
            }
            val episode = TitleParser.parseEpisode(title)
            if (episode != null) found++
            val inner = hasInnerLineBreak(title)
            if (inner) withLineBreaks++
            assertEquals(formerEpisode(title, if (inner) formerWithAnyTail else former), episode, "case $case: ${describe(title)}")
            assertEquals(formerYear(title), TitleParser.splitYear(title), "case $case: ${describe(title)}")
        }
        assert(found > 1_000 && withLineBreaks > 1_000) { "found $found, $withLineBreaks with line breaks: the generator misses cases" }
        // The one difference: an episode whose title holds a line break.
        val withBreak = "Show S01E02 Part one\u2028Part two"
        assertEquals(TitleParser.EpisodeInfo("Show", 1, 2, "Part one\u2028Part two"), TitleParser.parseEpisode(withBreak))
        assertEquals(null, formerEpisode(withBreak, former))
    }

    private companion object {
        const val CASES = 20_000
    }
}
