package io.github.pronzzz.wheelhouse

import io.github.pronzzz.wheelhouse.spool.MusicSaver
import io.github.pronzzz.wheelhouse.spool.SpoolLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpoolLinksTest {

    @Test fun findsYouTubeLinksInSharedText() {
        assertEquals("https://youtu.be/dQw4w9WgXcQ?si=abc", SpoolLinks.extract("Watch this! https://youtu.be/dQw4w9WgXcQ?si=abc"))
        assertEquals("https://music.youtube.com/playlist?list=PL1", SpoolLinks.extract("https://music.youtube.com/playlist?list=PL1."))
        assertEquals("https://www.youtube.com/watch?v=x", SpoolLinks.extract("see https://example.com then https://www.youtube.com/watch?v=x"))
    }

    @Test fun ignoresOtherSitesAndLookalikes() {
        assertNull(SpoolLinks.extract("https://example.com/youtube.com"))
        assertNull(SpoolLinks.extract("https://youtube.com.evil.example/watch?v=x"))
        assertNull(SpoolLinks.extract("just text"))
        assertNull(SpoolLinks.extract(null))
    }

    @Test fun parsesTypedAddresses() {
        assertEquals("192.168.1.20" to 7317, SpoolLinks.parseAddress("192.168.1.20"))
        assertEquals("192.168.1.20" to 7400, SpoolLinks.parseAddress(" 192.168.1.20:7400 "))
        assertEquals("10.0.2.2" to 7317, SpoolLinks.parseAddress("http://10.0.2.2/"))
        assertEquals("fe80::1" to 7317, SpoolLinks.parseAddress("[fe80::1]:7317"))
        assertNull(SpoolLinks.parseAddress(""))
        assertNull(SpoolLinks.parseAddress("192.168.1.20:99999"))
    }

    @Test fun savedFileNamesAreSafe() {
        assertEquals("AC DC - Back in Black.m4a", MusicSaver.safeName("AC/DC - Back in Black.m4a"))
        assertEquals("hidden.mp3", MusicSaver.safeName("..hidden.mp3"))
        assertEquals("audio/mpeg", MusicSaver.mimeOf("x.MP3"))
        assertEquals("audio/mp4", MusicSaver.mimeOf("x.m4a"))
    }
}
