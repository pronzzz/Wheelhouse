package io.github.pronzzz.wheelhouse.ui.screens

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import io.github.pronzzz.wheelhouse.spool.Download
import io.github.pronzzz.wheelhouse.spool.MusicSaver
import io.github.pronzzz.wheelhouse.spool.SpoolClient
import io.github.pronzzz.wheelhouse.spool.SpoolDiscovery
import io.github.pronzzz.wheelhouse.spool.SpoolException
import io.github.pronzzz.wheelhouse.spool.SpoolHost
import io.github.pronzzz.wheelhouse.spool.SpoolLinks
import io.github.pronzzz.wheelhouse.spool.SpoolListing
import io.github.pronzzz.wheelhouse.spool.SpoolVideo
import io.github.pronzzz.wheelhouse.ui.screen.MenuScreen
import io.github.pronzzz.wheelhouse.ui.screen.Row
import io.github.pronzzz.wheelhouse.ui.screen.Ui
import io.github.pronzzz.wheelhouse.util.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Spool: search YouTube or open a shared link, and download songs through the Spool engine on a
 * computer on the same Wi-Fi. The computer runs yt-dlp; the songs land in Music/Spool here.
 */
object SpoolMenus {

    /** Pairing codes avoid 0/O and 1/I. */
    private const val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val CODE_LENGTH = 6

    /**
     * A list screen whose rows depend on something that changes on its own (downloads, network
     * results). It rebuilds while visible whenever [watch] emits.
     */
    private open class LiveMenu(ui: Ui, title: String, empty: String = "No Items", build: () -> List<Row>) :
        MenuScreen(ui, title, empty, build) {
        private var job: Job? = null
        override fun onShown() {
            super.onShown()
            job?.cancel()
            job = ui.app.scope.launch { ui.app.spool.list.collect { refresh() } }
        }
        override fun onHidden() { job?.cancel(); job = null }
        override fun onDisposed() { job?.cancel(); job = null }
    }

    // ---- Top level ----------------------------------------------------------------------------

    fun root(ui: Ui): MenuScreen {
        lateinit var screen: MenuScreen
        val dl = ui.app.spool
        val prefs = ui.app.prefs
        screen = LiveMenu(ui, "Spool") {
            val host = dl.host()
            buildList {
                if (host == null) {
                    add(Row("Download songs through Spool", selectable = false))
                    add(Row("running on your computer.", selectable = false))
                    add(Row("Find Spool on Wi-Fi", chevron = true, onSelect = { ui.push(find(ui)) }))
                    add(Row("Enter Address…", chevron = true, onSelect = { ui.push(enterAddress(ui)) }))
                    add(Row("How to Set Up", chevron = true, onSelect = { ui.push(help(ui)) }))
                } else {
                    add(Row("Search YouTube", chevron = true, onSelect = { ui.push(searchEntry(ui)) }))
                    add(Row("Paste Link", chevron = true, onSelect = { pasteLink(ui) }))
                    val active = dl.activeCount
                    add(Row("Downloads", detail = if (active > 0) "$active active" else null, chevron = true,
                        onSelect = { ui.push(downloads(ui)) }))
                    add(Row("Format", detail = if (prefs.spoolFormat == "mp3") "MP3 320" else "M4A", onSelect = {
                        prefs.spoolFormat = if (prefs.spoolFormat == "mp3") "m4a" else "mp3"
                        screen.refresh()
                    }))
                    add(Row("Computer", detail = host.name, chevron = true, onSelect = { ui.push(computer(ui)) }))
                    add(Row("Songs go to Music › Spool.", selectable = false))
                }
            }
        }
        return screen
    }

    private fun help(ui: Ui) = MenuScreen(ui, "How to Set Up") {
        listOf(
            "On your computer:",
            "1. Install yt-dlp and ffmpeg",
            "2. Start Spool with --lan",
            "3. Note the 6-letter code",
            "On this phone:",
            "4. Find Spool on Wi-Fi",
            "5. Enter the code",
            "Guide: getspool.vercel.app",
        ).map { Row(it, selectable = false) }
    }

    // ---- Pairing ------------------------------------------------------------------------------

    private fun find(ui: Ui): MenuScreen {
        var state: List<Row> = listOf(Row("Looking on this Wi-Fi…", selectable = false))
        lateinit var screen: MenuScreen
        fun search() {
            val net = ui.app.network.state.value
            if (!net.wifi) {
                state = listOf(Row("Connect to Wi-Fi first.", selectable = false), Row("Try Again", onSelect = { search() }))
                screen.refresh()
                return
            }
            state = listOf(Row("Looking on this Wi-Fi…", selectable = false))
            screen.refresh()
            ui.app.scope.launch {
                val found = withContext(Dispatchers.IO) { runCatching { SpoolDiscovery.find(ui.activity) }.getOrDefault(emptyList()) }
                state = buildList {
                    if (found.isEmpty()) {
                        add(Row("No Spool found.", selectable = false))
                        add(Row("Is it running with --lan?", selectable = false))
                    }
                    found.forEach { f ->
                        add(Row(f.name, subtitle = "${f.host}:${f.port}", chevron = true,
                            onSelect = { ui.push(enterCode(ui, f.host, f.port, f.name)) }))
                    }
                    add(Row("Search Again", onSelect = { search() }))
                    add(Row("Enter Address…", chevron = true, onSelect = { ui.push(enterAddress(ui)) }))
                }
                screen.refresh()
            }
        }
        screen = object : MenuScreen(ui, "Find Spool", build = { state }) {
            private var started = false
            override fun onShown() {
                super.onShown()
                if (!started) { started = true; search() }
            }
        }
        return screen
    }

    private fun enterAddress(ui: Ui) = TextEntryScreen(ui, "Computer Address",
        ui.app.prefs.spoolHost.ifBlank { "192.168." },
        hint = { if (it.isBlank()) "The address Spool shows" else "e.g. 192.168.1.20:7317" },
        alphabet = "0123456789.:",
        onDone = { text ->
            val addr = SpoolLinks.parseAddress(text)
            if (addr == null) ui.flash("Check the Address")
            else ui.push(enterCode(ui, addr.first, addr.second, addr.first))
        })

    private fun enterCode(ui: Ui, host: String, port: Int, name: String) = TextEntryScreen(ui, "Pairing Code", "",
        hint = { if (it.length < CODE_LENGTH) "The 6-letter code Spool shows" else "Choose OK to connect" },
        alphabet = CODE_CHARS, upperCase = true, maxLength = CODE_LENGTH,
        onDone = { code ->
            if (code.length != CODE_LENGTH) {
                ui.flash("The Code Has 6 Letters")
                return@TextEntryScreen
            }
            ui.flash("Connecting…")
            ui.app.scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { SpoolClient(SpoolHost(host, port, code, name)).hello() } }
                result.onSuccess { hello ->
                    val prefs = ui.app.prefs
                    prefs.spoolHost = host
                    prefs.spoolPort = port
                    prefs.spoolCode = code
                    prefs.spoolName = hello.name.ifBlank { name }
                    if (hello.formats.isEmpty()) ui.flash("Install ffmpeg on the Computer")
                    else ui.flash("Connected to ${shortName(prefs.spoolName)}")
                    // Back to a fresh Spool menu, whichever way we came.
                    ui.popToRoot()
                    ui.push(root(ui))
                }.onFailure { ui.flash(problem(it)) }
            }
        })

    private fun computer(ui: Ui): MenuScreen {
        val prefs = ui.app.prefs
        return MenuScreen(ui, "Computer") {
            listOf(
                Row("Name", detail = prefs.spoolName),
                Row("Address", detail = "${prefs.spoolHost}:${prefs.spoolPort}"),
                Row("Test Connection", onSelect = {
                    val client = ui.app.spool.client() ?: return@Row
                    ui.flash("Checking…")
                    ui.app.scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { client.hello() } }
                        ui.flash(r.fold({ "Spool ${it.version} Is Ready" }, { problem(it) }))
                    }
                }),
                Row("Use Another Computer", chevron = true, onSelect = { ui.push(find(ui)) }),
                Row("Forget This Computer", onSelect = {
                    ui.push(Menus.confirm(ui, "Forget ${shortName(prefs.spoolName)}?", "Forget") {
                        prefs.spoolHost = ""
                        prefs.spoolCode = ""
                        prefs.spoolName = ""
                        ui.pop(); ui.pop()
                        ui.flash("Forgotten")
                    })
                }),
            )
        }
    }

    // ---- Search, links and results --------------------------------------------------------------

    private fun searchEntry(ui: Ui) = TextEntryScreen(ui, "Search YouTube", "",
        hint = { if (it.isBlank()) "Spell a song or artist" else "Choose OK to search" },
        onDone = { q -> if (q.isNotBlank()) ui.push(results(ui, "“$q”", allowAll = false) { it.search(q) }) })

    private fun pasteLink(ui: Ui) {
        val cm = ui.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(ui.activity)?.toString() }.getOrNull()
        val url = SpoolLinks.extract(text)
        if (url == null) ui.flash("Copy a YouTube Link First") else ui.push(link(ui, url))
    }

    /** Opens a YouTube link shared from another app (or pasted). */
    fun link(ui: Ui, url: String): MenuScreen =
        if (ui.app.spool.host() == null) root(ui).also { ui.flash("Connect to Spool First") }
        else results(ui, "Link", allowAll = true) { it.inspect(url) }

    private fun results(ui: Ui, heading: String, allowAll: Boolean, load: (SpoolClient) -> SpoolListing): MenuScreen {
        val dl = ui.app.spool
        var listing: SpoolListing? = null
        var error: String? = null
        lateinit var screen: MenuScreen
        fun fetch() {
            val client = dl.client() ?: return
            listing = null
            error = null
            ui.app.scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { load(client) } }
                r.onSuccess { listing = it }.onFailure { error = problem(it, long = true) }
                screen.refresh()
                ui.refreshTitle()
            }
        }
        screen = object : LiveMenu(ui, heading, "No Results", build = {
            val l = listing
            val e = error
            when {
                e != null -> listOf(Row(e, selectable = false), Row("Try Again", onSelect = { fetch(); screen.refresh() }))
                l == null -> listOf(Row(if (allowAll) "Reading the link…" else "Searching YouTube…", selectable = false))
                else -> buildList {
                    val wanted = l.items.filterNot { it.unavailable || dl.have(it.id) }
                    if (allowAll && l.items.size > 1) {
                        add(Row("Download All", detail = wanted.size.toString(), onSelect = { download(ui, l.title, wanted) }))
                    }
                    l.items.forEach { v ->
                        add(Row(
                            title = v.title,
                            subtitle = listOfNotNull(v.channel, v.duration?.let { Text.duration(it * 1000L) }).joinToString(" · ").ifEmpty { null },
                            detail = status(ui, v),
                            onSelect = { download(ui, l.title, listOf(v)) },
                        ))
                    }
                }
            }
        }) {
            override val title: String get() = listing?.takeIf { allowAll }?.title?.ifBlank { null } ?: heading
        }
        fetch()
        return screen
    }

    private fun status(ui: Ui, v: SpoolVideo): String? {
        if (v.unavailable) return "—"
        val d = ui.app.spool.stateOf(v.id)
        return when {
            ui.app.spool.have(v.id) || d?.state == Download.State.SAVED -> "✓"
            d == null -> null
            d.state == Download.State.DOWNLOADING -> "${d.progress}%"
            d.state == Download.State.FAILED -> "!"
            d.active -> "…"
            else -> null
        }
    }

    private fun download(ui: Ui, title: String, videos: List<SpoolVideo>) {
        if (MusicSaver.needsPermission(ui.activity)) {
            if (Build.VERSION.SDK_INT >= 23) ui.activity.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            ui.flash("Allow Storage, Then Try Again")
            return
        }
        val n = ui.app.spool.enqueue(title, videos)
        ui.flash(when (n) {
            0 -> if (videos.size == 1 && videos[0].unavailable) "Not Available" else "Already Downloaded"
            1 -> "Downloading…"
            else -> "Downloading $n Songs"
        })
    }

    // ---- Downloads ----------------------------------------------------------------------------

    private fun downloads(ui: Ui): MenuScreen {
        val dl = ui.app.spool
        return LiveMenu(ui, "Downloads", "No Downloads\nSearch or paste a link\nto get songs") {
            val list = dl.list.value
            buildList {
                list.forEach { d ->
                    add(Row(d.title, subtitle = d.label(), detail = if (d.state == Download.State.SAVED) "✓" else null, onSelect = {
                        when {
                            d.state == Download.State.FAILED || d.state == Download.State.CANCELLED -> {
                                dl.retry(d); ui.flash("Trying Again")
                            }
                            d.active -> ui.push(Menus.confirm(ui, "Stop this download?", "Stop Download") {
                                dl.cancel(d); ui.pop(); ui.flash("Stopped")
                            })
                            else -> ui.flash("In Music › Songs")
                        }
                    }))
                }
                if (list.any { !it.active }) add(Row("Clear Finished", onSelect = { dl.clearFinished() }))
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    const val REQ_STORAGE = 11

    private fun shortName(name: String) = if (name.length > 18) name.take(17) + "…" else name

    /** A short, human message for a failure. [long] allows a full sentence (list rows). */
    private fun problem(e: Throwable, long: Boolean = false): String = when {
        e is SpoolException && e.status == 401 -> "Wrong Code"
        e is SpoolException && e.status == 429 -> "Too Many Tries. Wait 10 min."
        long && e is SpoolException -> e.message ?: "Spool had a problem."
        e is SpoolException && e.status in 400..499 -> e.message?.takeIf { it.length < 30 } ?: "Spool Said No"
        e is SpoolException && e.status >= 500 -> "Spool Had a Problem"
        else -> "Can't Reach Spool"
    }
}
