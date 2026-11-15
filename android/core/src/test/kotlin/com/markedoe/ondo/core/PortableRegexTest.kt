package com.markedoe.ondo.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Every regular expression of the apps' Android code compiles on Android, whose `java.util.regex` is ICU's engine, not
 * the JDK's the unit tests run on: ICU knows the inline flags `i`, `m`, `s`, `x` (and `w`, which the JDK does not), so
 * the JDK's `(?U)` (UNICODE_CHARACTER_CLASS), `(?u)`, `(?d)` or `(?c)` throw a PatternSyntaxException on a device while
 * every JVM and Robolectric test passes; Android refuses the UNICODE_CHARACTER_CLASS and CANON_EQ flags too. A pattern
 * kept in a class's static field then breaks the class for good: FilmVersions' did at launch (build 1081).
 * Every source file of every module is read, so a new one is checked too.
 */
class PortableRegexTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val modules = File("..").canonicalFile

    private fun sources(): List<File> = modules.listFiles().orEmpty()
        .map { File(it, "src/main") }
        .filter { it.isDirectory }
        .flatMap { main -> main.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList() }

    @Test
    fun theSourcesAreRead() {
        val names = sources().map { it.name }
        assertTrue("$modules: ${names.size} files", "FilmVersions.kt" in names && "MainActivity.kt" in names)
    }

    @Test
    fun noInlineFlagAndroidRejects() {
        // A group of flags: "(?U)", "(?iU:", "(?-U)"; not "(?:", "(?=", "(?<", "(?!".
        val flags = Regex("""\(\?([A-Za-z]*(?:-[A-Za-z]*)?)[:)]""")
        val found = sources().flatMap { file ->
            file.readLines().withIndex().flatMap { (index, line) ->
                flags.findAll(line).filter { group -> group.groupValues[1].any { it != '-' && it !in "imsx" } }
                    .map { "${file.relativeTo(modules)}:${index + 1}: ${it.value}" }
            }
        }
        assertTrue("inline flags Android's regex engine (ICU) refuses:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun noFlagAndroidRejects() {
        val refused = listOf("UNICODE_CHARACTER_CLASS", "CANON_EQ")
        val found = sources().flatMap { file ->
            file.readLines().withIndex().filter { (_, line) -> refused.any { it in line } }.map { (index, line) -> "${file.relativeTo(modules)}:${index + 1}: ${line.trim()}" }
        }
        assertTrue("regex flags Android refuses:\n" + found.joinToString("\n"), found.isEmpty())
    }
}
