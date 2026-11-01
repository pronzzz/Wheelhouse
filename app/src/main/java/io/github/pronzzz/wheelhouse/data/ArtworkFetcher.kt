package io.github.pronzzz.wheelhouse.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import io.github.pronzzz.wheelhouse.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Finds covers for albums that have none, using two free public catalogues: Deezer first,
 * then Apple's iTunes Search. Only the album and artist names are sent. Covers are saved in
 * the app's own storage, never written into your music files.
 */
class ArtworkFetcher(context: Context, private val prefs: Prefs, private val net: NetworkMonitor) {

    private val dir = File(context.filesDir, "artwork").apply { mkdirs() }
    /** Albums looked up without success, so we don't ask again every time they scroll by. */
    private val misses = context.getSharedPreferences("artwork_misses", Context.MODE_PRIVATE)
    private var lastItunes = 0L

    /** Whether lookups are allowed right now (setting on, online, Wi-Fi if required). */
    fun allowed(): Boolean {
        val s = net.state.value
        return prefs.fetchArtwork && s.online && (!prefs.artworkWifiOnly || s.wifi || !s.metered)
    }

    fun cached(album: String, artist: String?): File? =
        File(dir, ArtMatch.key(album, artist) + ".jpg").takeIf { it.isFile && it.length() > 0 }

    /** Recently failed lookups are skipped unless [force] is set. */
    fun recentlyMissed(album: String, artist: String?): Boolean {
        val at = misses.getLong(ArtMatch.key(album, artist), 0L)
        return at > 0 && System.currentTimeMillis() - at < RETRY_AFTER_MS
    }

    fun downloadedCount(): Int = dir.listFiles()?.count { it.name.endsWith(".jpg") } ?: 0

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        misses.edit().clear().apply()
    }

    /**
     * Looks the album up and saves its cover. Blocking network I/O: call off the main thread.
     * Returns the saved file, or null if nothing convincing was found.
     */
    fun fetch(album: String, artist: String?, force: Boolean = false): File? {
        if (!ArtMatch.searchable(album, artist)) return null
        cached(album, artist)?.let { return it }
        if (!force && recentlyMissed(album, artist)) return null
        if (!allowed()) return null
        val key = ArtMatch.key(album, artist)
        val query = listOfNotNull(artist?.takeUnless { ArtMatch.isUnknownArtist(it) }, album).joinToString(" ")

        var networkError = false
        for (source in listOf(::deezer, ::itunes)) {
            val match = try {
                ArtMatch.best(source(query), album, artist)
            } catch (_: IOException) {
                networkError = true
                null
            } catch (_: Exception) {
                null
            } ?: continue
            val file = try { save(match.imageUrl, key) } catch (_: Exception) { networkError = true; null }
            if (file != null) {
                misses.edit().remove(key).apply()
                return file
            }
        }
        // A dropped connection is not a "miss"; try again next time.
        if (!networkError) misses.edit().putLong(key, System.currentTimeMillis()).apply()
        return null
    }

    private fun deezer(query: String): List<ArtCandidate> {
        val json = JSONObject(get("https://api.deezer.com/search/album?limit=10&q=" + enc(query)).decodeToString())
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val a = data.getJSONObject(i)
            val img = a.optString("cover_big").ifEmpty { a.optString("cover_medium") }
            if (img.isEmpty()) null
            else ArtCandidate(a.optString("title"), a.optJSONObject("artist")?.optString("name").orEmpty(), img)
        }
    }

    private fun itunes(query: String): List<ArtCandidate> {
        // Apple allows about 20 lookups a minute.
        val wait = 3_000 - (SystemClock.elapsedRealtime() - lastItunes)
        if (wait > 0) Thread.sleep(wait)
        lastItunes = SystemClock.elapsedRealtime()
        val json = JSONObject(get("https://itunes.apple.com/search?media=music&entity=album&limit=10&term=" + enc(query)).decodeToString())
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val a = results.getJSONObject(i)
            val img = a.optString("artworkUrl100")
            if (img.isEmpty()) null
            else ArtCandidate(a.optString("collectionName"), a.optString("artistName"), img.replace("100x100bb", "600x600bb"))
        }
    }

    /** Downloads, shrinks to at most 600 px and saves as JPEG. */
    private fun save(url: String, key: String): File? {
        val bytes = get(url, maxBytes = 4 * 1024 * 1024)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth < 100 || opts.outHeight < 100) return null
        var sample = 1
        while (opts.outWidth / (sample * 2) >= MAX_PX) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scaled = if (bmp.width > MAX_PX) Bitmap.createScaledBitmap(bmp, MAX_PX, bmp.height * MAX_PX / bmp.width, true) else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
        val tmp = File(dir, "$key.tmp")
        tmp.writeBytes(out.toByteArray())
        val file = File(dir, "$key.jpg")
        if (!tmp.renameTo(file)) { tmp.delete(); return null }
        return file
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val MAX_PX = 600
        private const val RETRY_AFTER_MS = 14L * 24 * 60 * 60 * 1000
        val USER_AGENT = "Wheelhouse/${BuildConfig.VERSION_NAME} (+https://github.com/pronzzz/Wheelhouse)"

        /** A small, dependency-free HTTP GET with sane timeouts. */
        fun get(url: String, maxBytes: Int = 1024 * 1024): ByteArray {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                c.setRequestProperty("User-Agent", USER_AGENT)
                c.setRequestProperty("Accept", "application/json, image/*")
                val code = c.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                c.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > maxBytes) throw IOException("Response too large")
                    }
                    return out.toByteArray()
                }
            } finally {
                c.disconnect()
            }
        }
    }
}
