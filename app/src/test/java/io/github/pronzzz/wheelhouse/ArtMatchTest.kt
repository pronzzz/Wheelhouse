package io.github.pronzzz.wheelhouse

import io.github.pronzzz.wheelhouse.data.ArtCandidate
import io.github.pronzzz.wheelhouse.data.ArtMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtMatchTest {

    private fun c(album: String, artist: String) = ArtCandidate(album, artist, "https://img/$album/$artist")

    @Test fun normalizeDropsEditionsAccentsAndPunctuation() {
        assertEquals("discovery", ArtMatch.normalize("Discovery (Remastered 2021)"))
        assertEquals("beyonce", ArtMatch.normalize("Beyoncé"))
        assertEquals("beatles", ArtMatch.normalize("The Beatles"))
        assertEquals("rock and roll", ArtMatch.normalize("Rock & Roll!"))
        assertEquals("homework", ArtMatch.normalize("Homework - Deluxe Edition"))
    }

    @Test fun picksTheMatchingAlbumAndArtist() {
        val list = listOf(
            c("Random Access Memories", "Daft Punk"),
            c("Discovery", "Daft Punk"),
            c("Discovery", "Some Cover Band"),
        )
        assertEquals(list[1], ArtMatch.best(list, "Discovery", "Daft Punk"))
    }

    @Test fun acceptsEditionsOfTheSameAlbum() {
        val list = listOf(c("Homework (25th Anniversary Edition)", "Daft Punk"))
        assertEquals(list[0], ArtMatch.best(list, "Homework", "Daft Punk"))
    }

    @Test fun rejectsAWrongArtist() {
        assertNull(ArtMatch.best(listOf(c("Discovery", "Electric Light Orchestra")), "Discovery", "Daft Punk"))
    }

    @Test fun rejectsADifferentAlbum() {
        assertNull(ArtMatch.best(listOf(c("Alive 2007", "Daft Punk")), "Discovery", "Daft Punk"))
    }

    @Test fun unknownArtistNeedsAnExactTitle() {
        val list = listOf(c("Kind of Blue (Legacy Edition)", "Miles Davis"), c("Kind of Blue", "Miles Davis"))
        assertEquals(list[1], ArtMatch.best(list, "Kind of Blue", "Unknown Artist"))
        assertNull(ArtMatch.best(listOf(c("Kind of Blue Live", "X")), "Kind of Blue", null))
    }

    @Test fun genericOrUnknownAlbumsAreNotSearched() {
        assertFalse(ArtMatch.searchable("Unknown Album", "Daft Punk"))
        assertFalse(ArtMatch.searchable("Greatest Hits", "Unknown Artist"))
        assertTrue(ArtMatch.searchable("Greatest Hits", "Queen"))
        assertTrue(ArtMatch.searchable("Discovery", null))
    }

    @Test fun keyIsStableAndIgnoresCosmeticDifferences() {
        assertEquals(ArtMatch.key("Discovery", "Daft Punk"), ArtMatch.key("discovery ", "DAFT PUNK"))
        assertEquals(ArtMatch.key("Abbey Road", "Unknown Artist"), ArtMatch.key("Abbey Road", null))
        assertNotEquals(ArtMatch.key("Discovery", "Daft Punk"), ArtMatch.key("Homework", "Daft Punk"))
    }
}
