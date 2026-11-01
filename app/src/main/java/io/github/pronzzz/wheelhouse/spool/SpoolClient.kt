package io.github.pronzzz.wheelhouse.spool

import io.github.pronzzz.wheelhouse.data.ArtworkFetcher
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

/** A computer running Spool with --lan. */
data class SpoolHost(val host: String, val port: Int, val code: String, val name: String = host) {
    val base get() = "http://${if (host.contains(':')) "[$host]" else host}:$port/api/lan"
}

/** A video from a search or a link. */
data class SpoolVideo(val id: String, val title: String, val channel: String?, val duration: Int?, val unavailable: Boolean)

data class SpoolListing(val kind: String, val title: String, val items: List<SpoolVideo>)

data class SpoolHello(val name: String, val version: String, val formats: List<String>)

/** One video inside a Spool download job, as the engine reports it. */
data class SpoolItemState(val id: String, val state: String, val progress: Int, val file: String?, val error: String?)

data class SpoolJob(val id: String, val state: String, val items: List<SpoolItemState>)

/** Something the user should read ("Wrong pairing code."), not a stack trace. */
class SpoolException(message: String, val status: Int = 0) : IOException(message)

/**
 * Talks to the Spool engine on a computer over the home network. Spool runs yt-dlp there;
 * the phone only searches, asks for downloads and collects the finished files.
 */
class SpoolClient(private val host: SpoolHost) {

    fun hello(): SpoolHello {
        val j = json("GET", "/hello")
        return SpoolHello(j.optString("name", host.name), j.optString("version"), j.optJSONArray("formats").strings())
    }

    fun search(query: String, limit: Int = 20): SpoolListing =
        listing(json("GET", "/search?limit=$limit&q=" + URLEncoder.encode(query, "UTF-8"), timeoutMs = 90_000))

    fun inspect(url: String): SpoolListing =
        listing(json("POST", "/inspect", JSONObject().put("url", url), timeoutMs = 120_000))

    fun createJob(title: String, videos: List<SpoolVideo>, format: String): SpoolJob {
        val items = JSONArray()
        videos.forEachIndexed { i, v -> items.put(JSONObject().put("id", v.id).put("index", i + 1).put("title", v.title)) }
        val body = JSONObject()
            .put("title", title)
            .put("kind", "video")
            .put("format", format)
            .put("quality", if (format == "mp3") "320" else "best")
            .put("items", items)
        return job(json("POST", "/jobs", body))
    }

    fun job(id: String): SpoolJob = job(json("GET", "/jobs/$id"))

    fun cancel(id: String) { json("POST", "/jobs/$id/cancel", JSONObject()) }

    /** Streams a finished file to [sink]. */
    fun download(jobId: String, videoId: String, sink: (InputStream, Long) -> Unit) {
        val c = open("GET", "/jobs/$jobId/files/$videoId", timeoutMs = 60_000)
        try {
            check(c)
            c.inputStream.use { sink(it, c.contentLength.toLong()) }
        } finally {
            c.disconnect()
        }
    }

    /** Tells the computer the phone has the file, so it can delete its copy. */
    fun collected(jobId: String, videoId: String) { json("DELETE", "/jobs/$jobId/files/$videoId") }

    // ---- plumbing ----------------------------------------------------------------------------

    private fun listing(j: JSONObject): SpoolListing {
        val arr = j.optJSONArray("items") ?: JSONArray()
        val items = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SpoolVideo(
                id = o.getString("id"),
                title = o.optString("title", "Untitled"),
                channel = o.optStringOrNull("channel"),
                duration = if (o.isNull("duration")) null else o.optInt("duration"),
                unavailable = o.optBoolean("unavailable"),
            )
        }
        return SpoolListing(j.optString("kind"), j.optString("title"), items)
    }

    private fun job(j: JSONObject): SpoolJob {
        val job = j.getJSONObject("job")
        val arr = j.optJSONArray("items") ?: JSONArray()
        val items = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SpoolItemState(o.getString("id"), o.optString("state"), o.optInt("progress"), o.optStringOrNull("file"), o.optStringOrNull("error"))
        }
        return SpoolJob(job.getString("id"), job.optString("state"), items)
    }

    private fun open(method: String, path: String, timeoutMs: Int): HttpURLConnection {
        val c = URL(host.base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 6_000
        c.readTimeout = timeoutMs
        c.setRequestProperty("Authorization", "Bearer ${host.code}")
        c.setRequestProperty("User-Agent", ArtworkFetcher.USER_AGENT)
        return c
    }

    private fun json(method: String, path: String, body: JSONObject? = null, timeoutMs: Int = 15_000): JSONObject {
        val c = open(method, path, timeoutMs)
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            check(c)
            return JSONObject(c.inputStream.use { it.readBytes().decodeToString() })
        } catch (e: SocketTimeoutException) {
            throw SpoolException("Spool took too long to answer.")
        } catch (e: SpoolException) {
            throw e
        } catch (e: IOException) {
            throw SpoolException("Can't reach ${host.name}. Is Spool running with --lan?")
        } finally {
            c.disconnect()
        }
    }

    private fun check(c: HttpURLConnection) {
        val code = c.responseCode
        if (code in 200..299) return
        val message = runCatching {
            JSONObject(c.errorStream.use { it.readBytes().decodeToString() }).optString("error")
        }.getOrNull()?.takeIf { it.isNotBlank() }
        throw SpoolException(message ?: "Spool answered with an error ($code).", code)
    }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
    private fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }
}
