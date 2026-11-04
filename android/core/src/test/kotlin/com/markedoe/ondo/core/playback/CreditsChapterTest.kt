package com.markedoe.ondo.core.playback

import com.markedoe.ondo.core.HangGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule

/** The end credits among a file's chapters. The Swift core's `CreditsChapterTests` has the same cases. */
class CreditsChapterTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @Test
    fun creditsNamesInSeveralLanguages() {
        val names = listOf(
            "Credits", "End Credits", "CLOSING CREDITS", "End titles", "Générique", "Generique de fin", "Générique de fin",
            "Abspann", "Créditos", "Créditos finales", "Créditos finais", "Titoli di coda", "Aftiteling", "Napisy końcowe",
            "Титры", "片尾", "片尾曲", "エンディング", "엔딩 크레딧", "엔딩크레딧", "Ending", "Outro", "Chapter 12 - End Credits",
            "Mid-Credits Scene", "  credits  ",
        )
        for (name in names) assertTrue(CreditsChapter.isCredits(name), name)
        val others = listOf("Chapter 12", "The Bending Bridge", "Discredits", "Final battle", "Epilogue", "Generic", "")
        for (name in others) assertFalse(CreditsChapter.isCredits(name), name)
    }

    @Test
    fun onlyTheLastQuarterCounts() {
        val opening = MediaChapter("Opening Credits", 0.0)
        assertNull(CreditsChapter.start(listOf(opening), 6_000.0), "opening credits")
        assertNull(CreditsChapter.start(listOf(MediaChapter("Credits", 4_499.0)), 6_000.0), "just before the last quarter")
        assertEquals(4_500.0, CreditsChapter.start(listOf(MediaChapter("Credits", 4_500.0)), 6_000.0), "at three quarters")
        assertNull(CreditsChapter.start(listOf(MediaChapter("Credits", 6_000.0)), 6_000.0), "at the very end")
        assertNull(CreditsChapter.start(listOf(MediaChapter("Credits", 5_000.0)), 0.0), "no duration")
        val chapters = listOf(
            opening, MediaChapter("Chapter 2", 600.0), MediaChapter("Post-credits scene", 5_700.0),
            MediaChapter("End Credits", 5_400.0), MediaChapter(null, 5_900.0),
        )
        assertEquals(5_400.0, CreditsChapter.start(chapters, 6_000.0), "the earliest credits chapter")
    }

    @Test
    fun noCreditsChapter() {
        assertNull(CreditsChapter.start(emptyList(), 6_000.0))
        val chapters = listOf(MediaChapter("Chapter 1", 0.0), MediaChapter("Chapter 9", 5_400.0), MediaChapter(null, 5_700.0))
        assertNull(CreditsChapter.start(chapters, 6_000.0))
    }
}
