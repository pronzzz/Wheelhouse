package io.github.pronzzz.wheelhouse.spool

import android.content.Context
import io.github.pronzzz.wheelhouse.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** One song on its way from YouTube, through Spool on the computer, onto this phone. */
data class Download(
    val videoId: String,
    val title: String,
    val jobId: String?,
    val state: State,
    val progress: Int = 0,
    val error: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
) {
    enum class State { SENDING, WAITING, DOWNLOADING, CONVERTING, SAVING, SAVED, FAILED, CANCELLED }

    val active get() = state !in setOf(State.SAVED, State.FAILED, State.CANCELLED)

    fun label(): String = when (state) {
        State.SENDING -> "Sending to computer…"
        State.WAITING -> "Waiting"
        State.DOWNLOADING -> "Downloading $progress%"
        State.CONVERTING -> "Converting"
        State.SAVING -> "Copying to phone"
        State.SAVED -> "Saved"
        State.FAILED -> error ?: "Failed"
        State.CANCELLED -> "Cancelled"
    }
}

/**
 * The phone side of the Spool pipeline: asks the computer to download, follows progress,
 * collects each finished file into Music/Spool, then lets the computer delete its copy.
 * Downloads in progress are remembered across restarts.
 */
class SpoolDownloads(private val context: Context, private val prefs: Prefs) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = context.getSharedPreferences("spool", Context.MODE_PRIVATE)
    private val _list = MutableStateFlow(load())
    val list: StateFlow<List<Download>> = _list
    private var poller: Job? = null

    init {
        if (_list.value.any { it.active }) startPolling()
    }

    fun host(): SpoolHost? {
        val h = prefs.spoolHost.takeIf { it.isNotBlank() } ?: return null
        return SpoolHost(h, prefs.spoolPort, prefs.spoolCode, prefs.spoolName.ifBlank { h })
    }

    fun client(): SpoolClient? = host()?.let { SpoolClient(it) }

    /** Video ids already saved on this phone. */
    fun have(id: String): Boolean = store.getStringSet(KEY_HAVE, emptySet())!!.contains(id)

    fun stateOf(id: String): Download? = _list.value.firstOrNull { it.videoId == id }

    val activeCount get() = _list.value.count { it.active }

    /** Asks Spool to download these videos. Ones already saved or on their way are skipped. */
    fun enqueue(title: String, videos: List<SpoolVideo>): Int {
        val client = client() ?: return 0
        val fresh = videos.filter { v -> !v.unavailable && !have(v.id) && stateOf(v.id)?.active != true }.distinctBy { it.id }
        if (fresh.isEmpty()) return 0
        update { list -> fresh.map { Download(it.id, it.title, null, Download.State.SENDING) } + list.filterNot { d -> fresh.any { it.id == d.videoId } } }
        val format = prefs.spoolFormat
        scope.launch {
            try {
                val job = client.createJob(title, fresh, format)
                val ids = fresh.map { it.id }.toSet()
                update { list -> list.map { if (it.videoId in ids && it.jobId == null) it.copy(jobId = job.id, state = Download.State.WAITING) else it } }
                startPolling()
            } catch (e: Exception) {
                val ids = fresh.map { it.id }.toSet()
                update { list -> list.map { if (it.videoId in ids && it.jobId == null) it.copy(state = Download.State.FAILED, error = message(e)) else it } }
            }
        }
        return fresh.size
    }

    /** Tries a failed or cancelled song again as a new download. */
    fun retry(d: Download) {
        update { list -> list.filterNot { it.videoId == d.videoId } }
        enqueue(d.title, listOf(SpoolVideo(d.videoId, d.title, null, null, false)))
    }

    fun cancel(d: Download) {
        val jobId = d.jobId
        update { list -> list.map { if (it.videoId == d.videoId && it.active) it.copy(state = Download.State.CANCELLED) else it } }
        // A job may hold several songs; only cancel it on the computer if none of them is still wanted.
        if (jobId != null && _list.value.none { it.jobId == jobId && it.active }) {
            scope.launch { runCatching { client()?.cancel(jobId) } }
        }
    }

    fun clearFinished() = update { list -> list.filter { it.active } }

    private fun startPolling() {
        if (poller?.isActive == true) return
        poller = scope.launch {
            var failures = 0
            while (isActive) {
                val jobIds = _list.value.filter { it.active && it.jobId != null }.mapNotNull { it.jobId }.distinct()
                if (jobIds.isEmpty() && _list.value.none { it.state == Download.State.SENDING }) break
                val client = client()
                if (client == null) {
                    update { list -> list.map { if (it.active) it.copy(state = Download.State.FAILED, error = "Not connected to Spool") else it } }
                    break
                }
                for (jobId in jobIds) {
                    try {
                        follow(client, client.job(jobId))
                        failures = 0
                    } catch (e: SpoolException) {
                        if (e.status == 404) {
                            fail(jobId, "Spool restarted. Try again.")
                        } else if (++failures > MAX_FAILURES) {
                            fail(jobId, message(e))
                        }
                    } catch (e: Exception) {
                        if (++failures > MAX_FAILURES) fail(jobId, message(e))
                    }
                }
                delay(POLL_MS)
            }
        }
    }

    private fun fail(jobId: String, why: String) =
        update { list -> list.map { if (it.jobId == jobId && it.active) it.copy(state = Download.State.FAILED, error = why) else it } }

    private fun follow(client: SpoolClient, job: SpoolJob) {
        for (item in job.items) {
            val mine = _list.value.firstOrNull { it.videoId == item.id && it.jobId == job.id } ?: continue
            if (!mine.active || mine.state == Download.State.SAVING) continue
            when (item.state) {
                "queued" -> set(mine.copy(state = Download.State.WAITING))
                "downloading" -> set(mine.copy(state = Download.State.DOWNLOADING, progress = item.progress))
                "converting" -> set(mine.copy(state = Download.State.CONVERTING, progress = 99))
                "failed" -> set(mine.copy(state = Download.State.FAILED, error = item.error ?: "Spool couldn't download it"))
                "cancelled" -> set(mine.copy(state = Download.State.CANCELLED))
                "skipped" -> set(mine.copy(state = Download.State.FAILED, error = "Spool skipped it"))
                "done" -> collect(client, job.id, item, mine)
            }
        }
    }

    /** Copies a finished song from the computer into Music/Spool. */
    private fun collect(client: SpoolClient, jobId: String, item: SpoolItemState, d: Download) {
        val file = item.file ?: return set(d.copy(state = Download.State.FAILED, error = "Spool lost the file"))
        set(d.copy(state = Download.State.SAVING, progress = 100))
        try {
            client.download(jobId, item.id) { input, length -> MusicSaver.save(context, file, Complete(input, length)) }
            runCatching { client.collected(jobId, item.id) }
            store.edit().putStringSet(KEY_HAVE, store.getStringSet(KEY_HAVE, emptySet())!! + item.id).apply()
            set(d.copy(state = Download.State.SAVED, progress = 100))
        } catch (e: Exception) {
            set(d.copy(state = Download.State.FAILED, error = "Couldn't save: ${message(e)}"))
        }
    }

    private fun set(d: Download) = update { list -> list.map { if (it.videoId == d.videoId) d else it } }

    @Synchronized
    private fun update(change: (List<Download>) -> List<Download>) {
        val next = change(_list.value).take(MAX_KEPT)
        _list.value = next
        save(next)
    }

    private fun message(e: Exception) = e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong"

    // ---- persistence ------------------------------------------------------------------------

    private fun save(list: List<Download>) {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(JSONObject().put("v", d.videoId).put("t", d.title).put("j", d.jobId ?: "").put("s", d.state.name)
                .put("p", d.progress).put("e", d.error ?: "").put("a", d.addedAt))
        }
        store.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private fun load(): List<Download> = runCatching {
        val arr = JSONArray(store.getString(KEY_LIST, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            var state = Download.State.valueOf(o.getString("s"))
            // A copy cut short by a restart starts again; a request that never reached Spool failed.
            if (state == Download.State.SAVING) state = Download.State.WAITING
            val jobId = o.optString("j").ifEmpty { null }
            val error = o.optString("e").ifEmpty { null }
            if (state == Download.State.SENDING) {
                Download(o.getString("v"), o.getString("t"), jobId, Download.State.FAILED, 0, "Interrupted. Try again.", o.getLong("a"))
            } else {
                Download(o.getString("v"), o.getString("t"), jobId, state, o.optInt("p"), error, o.getLong("a"))
            }
        }
    }.getOrDefault(emptyList())

    /** Fails the save if the connection drops before the whole file arrived. */
    private class Complete(input: InputStream, private val expected: Long) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ else end() }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it >= 0) count += it else end() }
        private fun end() { if (expected > 0 && count != expected) throw IOException("the download was cut short") }
    }

    companion object {
        private const val KEY_LIST = "downloads"
        private const val KEY_HAVE = "have"
        private const val POLL_MS = 1_500L
        private const val MAX_FAILURES = 8
        private const val MAX_KEPT = 200
    }
}
