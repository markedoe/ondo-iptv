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

    @Test
    fun peopleAreFoundByName() {
        val index = PeopleIndex(listOf("Ada Vance, Tom Reyes", "Ada Vance (voice)", "Lena Moss", null, "Éva Adams, Tomás Ruiz"))
        assertEquals(listOf("Ada Vance", "Éva Adams"), index.names("ada"), "names starting with the query first")
        assertEquals(listOf("Tomás Ruiz"), index.names("TOMAS"), "accents and case ignored")
        assertEquals(listOf("Ada Vance"), index.names("vance ad"), "every word, in any order")
        assertEquals(emptyList(), index.names("to"), "three letters at least")
        assertEquals(listOf("Éva Adams"), index.names("eva"))
    }
}
