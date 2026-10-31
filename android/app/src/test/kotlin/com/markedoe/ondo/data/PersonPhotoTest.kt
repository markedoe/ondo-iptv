package com.markedoe.ondo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** A person kept before their photo was known gets it later, in My List and the collections; a known photo stays. */
class PersonPhotoTest {
    @Test
    fun aPersonKeptWithoutAPhotoGetsTheOneFoundLater() {
        val kept = Favorite.person("Ana Lima", null, 1)
        val library = LibraryState(
            favorites = listOf(kept, Favorite.person("Rui Costa", "https://image.example.com/rui.jpg", 2)),
            collections = listOf(UserCollection("c1", "Ana Lima", items = listOf(kept))),
        )
        val filled = library.withPersonPhoto("Ana Lima", "https://image.example.com/ana.jpg")
        assertEquals("https://image.example.com/ana.jpg", filled.favorites.first { it.itemId == "Ana Lima" }.imageUrl)
        assertEquals("https://image.example.com/ana.jpg", filled.collections.single().items.single().imageUrl)
        assertEquals("https://image.example.com/rui.jpg", filled.favorites.first { it.itemId == "Rui Costa" }.imageUrl)

        assertSame("nothing to fill: the same library", filled, filled.withPersonPhoto("Ana Lima", "https://image.example.com/other.jpg"))
        assertSame(library, library.withPersonPhoto("Nobody", "https://image.example.com/x.jpg"))
    }
}
