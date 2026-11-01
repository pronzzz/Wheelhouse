package io.github.pronzzz.wheelhouse.data

import java.text.Normalizer
import java.util.Locale

/** One album found by an online catalogue. */
data class ArtCandidate(val album: String, val artist: String, val imageUrl: String)

/**
 * Decides whether an album from an online catalogue is the one in the library. Kept free of
 * Android classes so it can be unit tested. It errs on the side of "no match": no cover is
 * better than the wrong cover.
 */
object ArtMatch {

    private val diacritics = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val brackets = Regex("[(\\[{][^)\\]}]*[)\\]}]")
    private val suffix = Regex("\\s+-\\s+(single|ep|deluxe.*|remaster.*|expanded.*|anniversary.*)$")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    /** Albums too generic to look up by title alone. */
    private val generic = setOf("", "unknown album", "greatest hits", "best of", "the best of", "hits", "live",
        "untitled", "music", "singles", "demo", "demos", "mixtape", "soundtrack", "download")

    private val unknownArtists = setOf("", "unknown artist", "various artists", "various", "va")

    fun normalize(s: String): String {
        var t = Normalizer.normalize(s, Normalizer.Form.NFD).replace(diacritics, "").lowercase(Locale.ROOT)
        t = t.replace(brackets, " ").replace(suffix, "").replace("&", " and ")
        t = t.replace(nonWord, " ").trim().replace(Regex("\\s+"), " ")
        if (t.startsWith("the ") && t.length > 4) t = t.substring(4)
        return t
    }

    fun isUnknownArtist(artist: String?) = artist == null || normalize(artist) in unknownArtists.map { normalize(it) }

    /** Whether an album is worth looking up at all. */
    fun searchable(album: String, artist: String?): Boolean {
        val a = normalize(album)
        if (a.isEmpty() || a == "unknown album") return false
        // A generic title is only safe to look up together with a real artist.
        return !(isUnknownArtist(artist) && (a in generic || a.length < 3))
    }

    /** 1.0 for the same name, a bit less if one contains the other, else word overlap. */
    fun similarity(a: String, b: String): Double {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        if (x.contains(y) || y.contains(x)) return 0.85
        val xs = x.split(' ').toSet()
        val ys = y.split(' ').toSet()
        return (xs intersect ys).size.toDouble() / (xs union ys).size
    }

    /** The best convincing match, or null. */
    fun best(candidates: List<ArtCandidate>, album: String, artist: String?): ArtCandidate? {
        val noArtist = isUnknownArtist(artist)
        return candidates.mapNotNull { c ->
            // Editions normalise to the same title; on a tie prefer the one spelled exactly the same.
            val exact = if (c.album.trim().equals(album.trim(), ignoreCase = true)) 0.01 else 0.0
            val t = similarity(c.album, album)
            if (t < 0.85) return@mapNotNull null
            if (noArtist) {
                // Title alone has to be exact.
                if (t < 1.0) return@mapNotNull null
                c to t + exact
            } else {
                val ar = similarity(c.artist, artist!!)
                if (ar < 0.6) return@mapNotNull null
                c to (t * 2 + ar + exact)
            }
        }.maxByOrNull { it.second }?.first
    }

    /** Stable file name for an album's downloaded cover. */
    fun key(album: String, artist: String?): String {
        val raw = normalize(album) + "|" + (if (isUnknownArtist(artist)) "" else normalize(artist!!))
        val md = java.security.MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
        return md.joinToString("") { "%02x".format(it) }.take(24)
    }
}
