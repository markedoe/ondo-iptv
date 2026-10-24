package com.markedoe.ondo.core.fuzz

import com.markedoe.ondo.core.TextNormalizer
import com.markedoe.ondo.core.library.People
import kotlin.test.assertEquals
import org.junit.Test

/** Cast and director fields come from the provider and are split into names on the main thread. */
class PeopleFuzzTest {
    private val long = 1_000_000

    @Test(timeout = 20_000)
    fun peopleNamesStayLinearOnLongFields() {
        for (run in listOf(" ", "(", "[", "((", " (", "(a")) {
            People.names("Ann Lee" + run.repeat(long / run.length) + "x")
        }
        assertEquals(listOf("Ann Lee", "Bo Chen"), People.names("Ann Lee (voice), Bo Chen [uncredited]"))
    }

    /** A long run of text without a separator is no name: it would open a page carrying all of it. */
    @Test
    fun aPartLongerThanAnyNameIsNoName() {
        val run = "A" + "b".repeat(300_000)
        assertEquals(listOf("Ann Lee"), People.names("$run, Ann Lee"))
        assertEquals(listOf("Ann Lee"), People.names("Ann Lee / $run (voice)"))
        assertEquals(listOf("A" + "b".repeat(People.MAX_NAME_LENGTH - 1)), People.names("A" + "b".repeat(People.MAX_NAME_LENGTH - 1)))
        assertEquals(emptyList(), People.names("A" + "b".repeat(People.MAX_NAME_LENGTH)))
        assertEquals(false, People.mentions(run, run))
    }

    /** The linear search finds the names the former regular expression found. */
    @Test(timeout = 30_000)
    fun peopleNamesMatchTheFormerSplitting() {
        val separators = Regex("[,;/|\\n]")
        val role = Regex("\\s*[(\\[][^)\\]]*[)\\]]")
        fun former(text: String): List<String> {
            val seen = HashSet<String>()
            return text.split(separators)
                .map { part -> part.replace(role, "").trim { it.isWhitespace() || it == '.' || it == '…' } }
                .filter { name -> name.length > 1 && name.any(Char::isLetter) && seen.add(TextNormalizer.normalize(name)) }
        }
        val fuzz = Fuzz(seed = 11)
        val pieces = listOf("(", ")", "[", "]", " ", "\t", "\n", ",", "/", "|", ";", ".", "…", "Ann", "Lee", "voice", "Bo", "é", "\u00A0")
        repeat(20_000) { case ->
            val text = buildString { repeat(1 + fuzz.int(14)) { append(fuzz.pick(pieces).repeat(1 + fuzz.int(3))) } }
            assertEquals(former(text), People.names(text), "case $case: ${describe(text)}")
        }
    }
}
