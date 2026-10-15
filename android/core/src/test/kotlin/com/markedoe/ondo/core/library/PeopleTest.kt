package com.markedoe.ondo.core.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Same cases as IPTVCore's PeopleTests. */
class PeopleTest {
    @Test
    fun namesAreSplitCleanedAndKeptOnce() {
        assertEquals(
            listOf("Ada Vance", "Tom Reyes", "Lena Moss", "Jo Park"),
            People.names("Ada Vance, Tom Reyes (voice), Lena Moss / Ada Vance; Jo Park..."),
        )
        assertEquals(emptyList(), People.names("N/A"))
        assertEquals(emptyList(), People.names(null))
    }

    @Test
    fun aFieldNamesAPersonWhateverTheAccents() {
        assertTrue(People.mentions("Lena Moss, Élodie Marçeau", "elodie marceau"))
        assertFalse(People.mentions("Lena Mossberg", "Lena Moss"))
    }
}
