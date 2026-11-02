package com.markedoe.ondo.core.playback

import java.text.Normalizer

/** A chapter of a film or an episode, as its file names it: its title and where it starts (seconds). */
data class MediaChapter(val title: String?, val start: Double)

/**
 * Finds the end credits among a file's chapters: a title is watched once playback reaches them
 * ([com.markedoe.ondo.core.model.HistoryEntry.isFinished]). The same rule as the Swift core's `CreditsChapter`, with the
 * same tests.
 */
object CreditsChapter {
    /** Only a chapter in this last share of the duration counts, so opening credits never match. */
    const val LAST_SHARE = 0.25

    /** Chapter names for end credits, in several languages (compared without case or accents). */
    val names = listOf(
        "credits", "end credits", "closing credits", "end titles", "ending", "outro",
        "generique", "generique de fin", "abspann", "nachspann", "creditos", "creditos finales", "creditos finais",
        "titoli di coda", "aftiteling", "eindtitels", "napisy koncowe", "titry", "титры", "финальные титры",
        "片尾", "片尾曲", "演职员表", "エンディング", "スタッフロール", "엔딩", "엔딩 크레딧",
    )

    private val normalizedNames = names.map(::normalized)
    private val nonspacingMarks = Regex("\\p{Mn}+")

    /** Where the end credits start (seconds): the first chapter named for them in the last quarter of [duration]. */
    fun start(chapters: List<MediaChapter>, duration: Double): Double? {
        if (!(duration > 0) || !duration.isFinite()) return null
        val earliest = duration * (1 - LAST_SHARE)
        return chapters
            .filter { it.start.isFinite() && it.start >= earliest && it.start < duration && isCredits(it.title.orEmpty()) }
            .minOfOrNull { it.start }
    }

    /**
     * Whether a chapter's title names end credits: one of [names] as whole words, or within the title for scripts
     * written without spaces (Chinese, Japanese, Korean).
     */
    fun isCredits(title: String): Boolean {
        val text = normalized(title)
        val words = words(text)
        return normalizedNames.any { name ->
            if (name.any(::isWrittenWithoutSpaces)) return@any text.contains(name)
            val nameWords = words(name)
            nameWords.isNotEmpty() && nameWords.size <= words.size &&
                (0..words.size - nameWords.size).any { words.subList(it, it + nameWords.size) == nameWords }
        }
    }

    /** Decomposed, without accents (nonspacing marks), in lower case. */
    internal fun normalized(text: String): String =
        nonspacingMarks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()

    /** The runs of letters and digits. */
    internal fun words(text: String): List<String> =
        text.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    private fun isWrittenWithoutSpaces(character: Char): Boolean = when (character.code) {
        in 0x1100..0x11FF, in 0x3040..0x30FF, in 0x3400..0x4DBF, in 0x4E00..0x9FFF, in 0xAC00..0xD7AF -> true
        else -> false
    }
}
