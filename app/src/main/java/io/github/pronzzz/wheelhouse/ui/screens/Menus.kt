package io.github.pronzzz.wheelhouse.ui.screens

import android.os.Build
import android.os.Environment
import android.os.StatFs
import io.github.pronzzz.wheelhouse.BuildConfig
import io.github.pronzzz.wheelhouse.data.Album
import io.github.pronzzz.wheelhouse.data.ArtworkLoader
import io.github.pronzzz.wheelhouse.data.Artist
import io.github.pronzzz.wheelhouse.data.db.Track
import io.github.pronzzz.wheelhouse.launcher.HomeRole
import io.github.pronzzz.wheelhouse.ui.device.Haptics
import io.github.pronzzz.wheelhouse.ui.device.Skins
import io.github.pronzzz.wheelhouse.ui.screen.ImageSource
import io.github.pronzzz.wheelhouse.ui.screen.MenuScreen
import io.github.pronzzz.wheelhouse.ui.screen.Row
import io.github.pronzzz.wheelhouse.ui.screen.Ui
import io.github.pronzzz.wheelhouse.util.Text
import kotlinx.coroutines.launch
import java.util.Locale

/** Builders for every list screen in the app. */
object Menus {

    // ---- Music --------------------------------------------------------------------------------

    fun music(ui: Ui) = MenuScreen(ui, "Music") {
        buildList {
            add(Row("Playlists", chevron = true, onSelect = { ui.push(playlists(ui)) }))
            add(Row("Artists", chevron = true, onSelect = { ui.push(artists(ui)) }))
            add(Row("Albums", chevron = true, onSelect = { ui.push(albums(ui)) }))
            add(Row("Songs", chevron = true, onSelect = { ui.push(songs(ui)) }))
            add(Row("Genres", chevron = true, onSelect = { ui.push(genres(ui)) }))
            add(Row("Search", chevron = true, onSelect = { ui.push(search(ui)) }))
            if (ui.app.player.state.value.hasQueue) add(Row("Up Next", chevron = true, onSelect = { ui.push(upNext(ui)) }))
        }
    }

    private const val NO_MUSIC = "No Music Found\nCopy songs to this phone,\nthen Settings › Rescan Library"

    fun songs(ui: Ui) = MenuScreen(ui, "Songs", NO_MUSIC) {
        songRows(ui, ui.app.library.index.value.songs, subtitle = false)
    }

    /** Rows for a list of songs: an optional Shuffle row, then each song. */
    fun songRows(
        ui: Ui,
        tracks: List<Track>,
        subtitle: Boolean = true,
        indexLetters: Boolean = true,
        playlistId: Long? = null,
        showNumbers: Boolean = false,
    ): List<Row> {
        val current = ui.app.player.state.value.trackId
        val rows = ArrayList<Row>(tracks.size + 1)
        if (tracks.size > 1) rows += Row("Shuffle", onSelect = {
            ui.app.player.playTracks(tracks, shuffle = true)
            ui.showNowPlaying()
        })
        tracks.forEachIndexed { i, t ->
            rows += Row(
                title = if (showNumbers && t.trackNumber > 0) "${t.trackNumber}. ${t.title}" else t.title,
                subtitle = if (subtitle) t.artist else null,
                detail = if (subtitle) Text.duration(t.durationMs) else null,
                playing = t.id == current,
                letter = if (indexLetters) Text.indexLetter(t.title) else null,
                onSelect = {
                    ui.app.player.playTracks(tracks, i)
                    ui.showNowPlaying()
                },
                onLong = { ui.push(trackOptions(ui, t, playlistId = playlistId, position = i)) },
            )
        }
        return rows
    }

    private fun albumArt(ui: Ui, a: Album) = ImageSource { ui.app.artwork.album(a.albumId, a.tracks.firstOrNull(), ArtworkLoader.Quality.THUMB) }

    fun albums(ui: Ui) = MenuScreen(ui, "Albums", NO_MUSIC) {
        ui.app.library.index.value.albums.map { a -> albumRow(ui, a) }
    }

    private fun albumRow(ui: Ui, a: Album, subtitleYear: Boolean = false) = Row(
        title = a.title,
        subtitle = if (subtitleYear && a.year > 0) "${a.year} · ${Text.plural(a.tracks.size, "song")}" else a.artist,
        image = albumArt(ui, a),
        chevron = true,
        letter = Text.indexLetter(a.title),
        onSelect = { ui.push(albumSongs(ui, a)) },
        onLong = {
            ui.app.player.playTracks(a.tracks)
            ui.showNowPlaying()
        },
    )

    fun albumSongs(ui: Ui, album: Album): MenuScreen = MenuScreen(ui, album.title) {
        val fresh = ui.app.library.index.value.album(album.albumId) ?: album
        songRows(ui, fresh.tracks, subtitle = fresh.artist == "Various Artists", indexLetters = false, showNumbers = true)
    }

    fun artists(ui: Ui) = MenuScreen(ui, "Artists", NO_MUSIC) {
        ui.app.library.index.value.artists.map { a ->
            Row(a.name, chevron = true, letter = Text.indexLetter(a.name), onSelect = { ui.push(artist(ui, a)) })
        }
    }

    fun artist(ui: Ui, artist: Artist): MenuScreen = MenuScreen(ui, artist.name) {
        val a = ui.app.library.index.value.artist(artist.name) ?: artist
        buildList {
            add(Row("All Songs", detail = a.tracks.size.toString(), chevron = true, onSelect = {
                ui.push(MenuScreen(ui, a.name) { songRows(ui, a.tracks, subtitle = false) })
            }))
            a.albums.forEach { add(albumRow(ui, it, subtitleYear = true)) }
        }
    }

    fun genres(ui: Ui) = MenuScreen(ui, "Genres", "No Genres\nSongs without a genre tag\nare listed under Songs") {
        ui.app.library.index.value.genres.map { g ->
            Row(g.name, detail = g.tracks.size.toString(), chevron = true, letter = Text.indexLetter(g.name), onSelect = {
                ui.push(MenuScreen(ui, g.name) {
                    val fresh = ui.app.library.index.value.genres.firstOrNull { it.name == g.name }?.tracks ?: g.tracks
                    songRows(ui, fresh)
                })
            })
        }
    }

    // ---- Playlists ----------------------------------------------------------------------------

    fun playlists(ui: Ui) = MenuScreen(ui, "Playlists") {
        val lib = ui.app.library
        buildList {
            add(Row("Loved", chevron = true, onSelect = { ui.push(smart(ui, "Loved", "Hold Select on a song\nand choose Love") { lib.loved() }) }))
            add(Row("Recently Added", chevron = true, onSelect = { ui.push(smart(ui, "Recently Added") { lib.recentlyAdded() }) }))
            add(Row("Most Played", chevron = true, onSelect = { ui.push(smart(ui, "Most Played", "Nothing played yet") { lib.mostPlayed() }) }))
            add(Row("Recently Played", chevron = true, onSelect = { ui.push(smart(ui, "Recently Played", "Nothing played yet") { lib.recentlyPlayed() }) }))
            lib.playlists.forEach { pl ->
                add(Row(pl.name, detail = lib.playlistTracks(pl.id).size.toString(), chevron = true,
                    onSelect = { ui.push(userPlaylist(ui, pl.id)) },
                    onLong = { ui.push(playlistOptions(ui, pl.id, pl.name)) }))
            }
            add(Row("New Playlist…", onSelect = { ui.push(newPlaylist(ui, null)) }))
        }
    }

    private fun smart(ui: Ui, title: String, empty: String = "No Songs", tracks: () -> List<Track>) =
        MenuScreen(ui, title, empty) { songRows(ui, tracks(), indexLetters = false) }

    fun userPlaylist(ui: Ui, id: Long) = object : MenuScreen(ui, ui.app.library.playlists.firstOrNull { it.id == id }?.name ?: "Playlist",
        "Empty Playlist\nHold Select on any song\nand choose Add to Playlist", { songRows(ui, ui.app.library.playlistTracks(id), indexLetters = false, playlistId = id) }) {
        override val title: String get() = ui.app.library.playlists.firstOrNull { it.id == id }?.name ?: "Playlist"
    }

    private fun newPlaylist(ui: Ui, track: Track?) = TextEntryScreen(ui, "New Playlist", ui.app.library.nextPlaylistName(),
        hint = { "Name your playlist" },
        onDone = { name ->
            ui.app.library.createPlaylist(name.ifBlank { ui.app.library.nextPlaylistName() }, track?.id)
            ui.pop()
            if (track != null) { ui.pop(); ui.pop() }
            ui.flash(if (track != null) "Added to $name" else "Playlist Created")
        })

    private fun playlistOptions(ui: Ui, id: Long, name: String) = MenuScreen(ui, name) {
        listOf(
            Row("Play", onSelect = { ui.app.player.playTracks(ui.app.library.playlistTracks(id)); ui.showNowPlaying() }),
            Row("Rename", onSelect = {
                ui.push(TextEntryScreen(ui, "Rename", name, hint = { "New name" }) { newName ->
                    if (newName.isNotBlank()) ui.app.library.renamePlaylist(id, newName)
                    ui.pop(); ui.pop()
                    ui.flash("Renamed")
                })
            }),
            Row("Delete", onSelect = {
                ui.push(confirm(ui, "Delete “$name”?", "Delete Playlist") {
                    ui.app.library.deletePlaylist(id)
                    ui.pop(); ui.pop()
                    ui.flash("Deleted")
                })
            }),
        )
    }

    // ---- Song options -------------------------------------------------------------------------

    fun trackOptions(ui: Ui, t: Track, fromNowPlaying: Boolean = false, playlistId: Long? = null, position: Int = -1) =
        MenuScreen(ui, t.title) {
            val lib = ui.app.library
            val player = ui.app.player
            buildList {
                if (!fromNowPlaying) {
                    add(Row("Play Next", onSelect = { player.playNext(t); ui.pop(); ui.flash("Playing Next") }))
                    add(Row("Add to Up Next", onSelect = { player.addToQueue(t); ui.pop(); ui.flash("Added to Up Next") }))
                } else {
                    add(Row("Up Next", chevron = true, onSelect = { ui.push(upNext(ui)) }))
                }
                add(Row("Add to Playlist", chevron = true, onSelect = { ui.push(choosePlaylist(ui, t)) }))
                val loved = lib.isLoved(t.id)
                add(Row(if (loved) "Unlove" else "Love", onSelect = {
                    lib.toggleLoved(t.id); ui.pop(); ui.flash(if (loved) "Removed from Loved" else "Loved")
                }))
                lib.index.value.album(t.albumId)?.let { a -> add(Row("Go to Album", chevron = true, onSelect = { ui.push(albumSongs(ui, a)) })) }
                lib.index.value.artist(t.artist)?.let { a -> add(Row("Go to Artist", chevron = true, onSelect = { ui.push(artist(ui, a)) })) }
                if (playlistId != null && position >= 0) {
                    add(Row("Remove from Playlist", onSelect = {
                        lib.removeFromPlaylist(playlistId, position); ui.pop(); ui.flash("Removed")
                    }))
                }
                add(Row("Info", chevron = true, onSelect = { ui.push(trackInfo(ui, t)) }))
            }
        }

    private fun choosePlaylist(ui: Ui, t: Track) = MenuScreen(ui, "Add to Playlist") {
        buildList {
            add(Row("New Playlist…", onSelect = { ui.push(newPlaylist(ui, t)) }))
            ui.app.library.playlists.forEach { pl ->
                add(Row(pl.name, onSelect = {
                    ui.app.library.addToPlaylist(pl.id, listOf(t.id))
                    ui.pop(); ui.pop()
                    ui.flash("Added to ${pl.name}")
                }))
            }
        }
    }

    private fun trackInfo(ui: Ui, t: Track) = MenuScreen(ui, "Info") {
        val s = ui.app.library.stats(t.id)
        listOfNotNull(
            Row("Title", detail = t.title),
            Row("Artist", detail = t.artist),
            Row("Album", detail = t.album),
            t.genre?.let { Row("Genre", detail = it) },
            if (t.year > 0) Row("Year", detail = t.year.toString()) else null,
            Row("Length", detail = Text.duration(t.durationMs)),
            Row("Plays", detail = s.playCount.toString()),
            Row("Format", detail = t.path?.substringAfterLast('.', "")?.uppercase(Locale.ROOT)?.ifEmpty { null } ?: "—"),
            Row("Size", detail = String.format(Locale.ROOT, "%.1f MB", t.size / 1_048_576.0)),
        )
    }

    fun upNext(ui: Ui) = MenuScreen(ui, "Up Next", "Nothing Queued") {
        val player = ui.app.player
        buildList {
            player.currentTrack()?.let { add(Row("Now: ${it.title}", subtitle = it.artist, selectable = false)) }
            player.upNext().forEach { (queueIndex, t) ->
                add(Row(t.title, subtitle = t.artist, onSelect = { player.jumpTo(queueIndex); ui.showNowPlaying() }))
            }
        }
    }

    // ---- Search -------------------------------------------------------------------------------

    fun search(ui: Ui) = TextEntryScreen(ui, "Search", "",
        hint = { q -> if (q.isBlank()) "Spell a song, artist or album" else Text.plural(searchCount(ui, q), "result") },
        onDone = { q -> if (q.isNotBlank()) ui.push(searchResults(ui, q)) })

    private fun matches(s: String, q: String) = Text.sortKey(s).contains(Text.sortKey(q)) || s.lowercase().contains(q.lowercase())

    private fun searchCount(ui: Ui, q: String): Int {
        val idx = ui.app.library.index.value
        return idx.artists.count { matches(it.name, q) } + idx.albums.count { matches(it.title, q) } + idx.songs.count { matches(it.title, q) }
    }

    private fun searchResults(ui: Ui, q: String) = MenuScreen(ui, "“$q”", "No Results") {
        val idx = ui.app.library.index.value
        val artists = idx.artists.filter { matches(it.name, q) }.take(50)
        val albums = idx.albums.filter { matches(it.title, q) }.take(50)
        val songs = idx.songs.filter { matches(it.title, q) || matches(it.artist, q) }.take(200)
        buildList {
            if (artists.isNotEmpty()) {
                add(Row("Artists", selectable = false))
                artists.forEach { a -> add(Row(a.name, chevron = true, onSelect = { ui.push(artist(ui, a)) })) }
            }
            if (albums.isNotEmpty()) {
                add(Row("Albums", selectable = false))
                albums.forEach { add(albumRow(ui, it)) }
            }
            if (songs.isNotEmpty()) {
                add(Row("Songs", selectable = false))
                addAll(songRows(ui, songs, indexLetters = false).drop(if (songs.size > 1) 1 else 0))
            }
        }
    }

    // ---- Extras -------------------------------------------------------------------------------

    fun extras(ui: Ui) = MenuScreen(ui, "Extras") {
        buildList {
            add(Row("Clock", chevron = true, onSelect = { ui.push(ClockScreen(ui)) }))
            add(Row("Stopwatch", chevron = true, onSelect = { ui.push(StopwatchScreen(ui)) }))
            add(Row("Sleep Timer", detail = ui.app.player.sleepTimer.label(), chevron = true, onSelect = { ui.push(sleepTimer(ui)) }))
            if (ui.app.prefs.showApps) add(Row("Apps", chevron = true, onSelect = { ui.push(apps(ui)) }))
        }
    }

    private fun sleepTimer(ui: Ui) = MenuScreen(ui, "Sleep Timer") {
        val timer = ui.app.player.sleepTimer
        val choices = listOf(15, 30, 45, 60, 90)
        buildList {
            add(Row("Off", checked = !timer.active, onSelect = { timer.cancel(); ui.pop(); ui.flash("Sleep Timer Off") }))
            choices.forEach { m ->
                add(Row("$m Minutes", onSelect = { timer.start(m); ui.pop(); ui.flash("Sleeping in $m min") }))
            }
            add(Row("End of Song", checked = timer.endOfSong, onSelect = { timer.startEndOfSong(); ui.pop(); ui.flash("Stops After This Song") }))
        }
    }

    private fun apps(ui: Ui) = MenuScreen(ui, "Apps", "No Apps") {
        val repo = ui.app.apps
        val px = (36 * ui.dp).toInt()
        repo.load().map { a ->
            Row(a.label, image = ImageSource { repo.icon(a, px) }, letter = Text.indexLetter(a.label), onSelect = {
                if (!repo.launch(a)) ui.flash("Can't open ${a.label}")
            })
        }
    }

    // ---- Settings -----------------------------------------------------------------------------

    fun settings(ui: Ui): MenuScreen {
        lateinit var screen: MenuScreen
        fun toggle(action: () -> Unit) = { action(); ui.settingsChanged(); screen.refresh() }
        val prefs = ui.app.prefs
        val player = ui.app.player
        fun onOff(b: Boolean) = if (b) "On" else "Off"
        screen = MenuScreen(ui, "Settings") {
            listOf(
                Row("About", chevron = true, onSelect = { ui.push(about(ui)) }),
                Row("Shuffle", detail = if (player.state.value.shuffle) "Songs" else "Off",
                    onSelect = toggle { player.setShuffle(!player.state.value.shuffle) }),
                Row("Repeat", detail = listOf("Off", "One", "All")[player.state.value.repeatMode.coerceIn(0, 2)],
                    onSelect = toggle { player.setRepeat((player.state.value.repeatMode + 1) % 3) }),
                Row("Device Colour", detail = Skins.byId(prefs.skin).label, chevron = true, onSelect = { ui.push(deviceColour(ui)) }),
                Row("Screen", detail = if (prefs.darkScreen) "Dark" else "Light", onSelect = toggle { prefs.darkScreen = !prefs.darkScreen }),
                Row("Backlight", detail = if (prefs.backlight < 0) "System" else "${prefs.backlight}%", onSelect = toggle {
                    prefs.backlight = when (prefs.backlight) { -1 -> 25; 25 -> 50; 50 -> 75; 75 -> 100; else -> -1 }
                }),
                Row("Clicker", detail = onOff(prefs.clickSound), onSelect = toggle { prefs.clickSound = !prefs.clickSound }),
                Row("Haptics", detail = Haptics.labels[prefs.hapticLevel.coerceIn(0, 3)], onSelect = toggle {
                    prefs.hapticLevel = (prefs.hapticLevel + 1) % Haptics.labels.size
                    if (prefs.hapticLevel != Haptics.OFF && !ui.previewHaptics()) ui.flash("No vibration motor")
                }),
                Row("Wheel Speed", detail = listOf("Slow", "Normal", "Fast")[prefs.wheelSensitivity.coerceIn(0, 2)],
                    onSelect = toggle { prefs.wheelSensitivity = (prefs.wheelSensitivity + 1) % 3 }),
                Row("Full Screen", detail = onOff(prefs.immersive), onSelect = toggle { prefs.immersive = !prefs.immersive }),
                Row("Always-On Display", detail = onOff(prefs.alwaysOn), onSelect = toggle {
                    prefs.alwaysOn = !prefs.alwaysOn
                    if (prefs.alwaysOn) ui.flash("Dims to a clock after 1 min")
                }),
                Row("Keep Screen On", detail = listOf("Off", "On Charger", "Always")[prefs.keepAwake.coerceIn(0, 2)],
                    onSelect = toggle { prefs.keepAwake = (prefs.keepAwake + 1) % 3 }),
                Row("Return to Now Playing", detail = onOff(prefs.autoNowPlaying), onSelect = toggle { prefs.autoNowPlaying = !prefs.autoNowPlaying }),
                Row("Lite Mode", detail = onOff(prefs.liteMode), onSelect = toggle { prefs.liteMode = !prefs.liteMode }),
                Row("Apps in Extras", detail = onOff(prefs.showApps), onSelect = toggle { prefs.showApps = !prefs.showApps }),
                Row("Album Artwork", chevron = true, onSelect = { ui.push(artworkSettings(ui)) }),
                Row("Home Screen", chevron = true, onSelect = { ui.push(homeScreen(ui)) }),
                Row("Rescan Library", onSelect = {
                    ui.app.artwork.clear()
                    ui.app.library.rescan(deep = true)
                    ui.flash("Scanning for Music…")
                }),
                Row("Reset Settings", onSelect = {
                    ui.push(confirm(ui, "Reset all settings?", "Reset") {
                        prefs.resetAppearanceAndBehaviour()
                        player.setShuffle(false); player.setRepeat(0)
                        ui.settingsChanged()
                        ui.pop()
                        screen.refresh()
                        ui.flash("Settings Reset")
                    })
                }),
            )
        }
        return screen
    }

    /** Online cover lookups for albums without artwork. */
    fun artworkSettings(ui: Ui): MenuScreen {
        val prefs = ui.app.prefs
        val art = ui.app.artwork
        fun onOff(b: Boolean) = if (b) "On" else "Off"
        lateinit var screen: MenuScreen
        fun toggle(action: () -> Unit) = { action(); art.retryMissing(); screen.refresh() }
        screen = object : MenuScreen(ui, "Album Artwork", build = {
            val b = art.batch.value
            val net = ui.app.network.state.value
            buildList {
                add(Row("Download Missing Artwork", detail = onOff(prefs.fetchArtwork), onSelect = toggle { prefs.fetchArtwork = !prefs.fetchArtwork }))
                add(Row("Only on Wi-Fi", detail = onOff(prefs.artworkWifiOnly), onSelect = toggle { prefs.artworkWifiOnly = !prefs.artworkWifiOnly }))
                add(Row(
                    if (b.running) "Searching… ${b.done} of ${b.total}" else "Find Missing Artwork Now",
                    detail = if (b.total > 0) "${b.found} found" else null,
                    onSelect = {
                        when {
                            b.running -> ui.flash("Already Searching")
                            !prefs.fetchArtwork -> ui.flash("Turn on Download first")
                            !art.fetcher.allowed() -> ui.flash(if (net.online) "Waiting for Wi-Fi" else "No Internet Connection")
                            else -> {
                                art.findMissing(ui.app.library.index.value.albums)
                                ui.flash("Searching for Covers…")
                                screen.refresh()
                            }
                        }
                    },
                ))
                add(Row("Downloaded Covers", detail = art.fetcher.downloadedCount().toString(), selectable = false))
                add(Row("Delete Downloaded Covers", onSelect = {
                    ui.push(confirm(ui, "Delete downloaded covers?", "Delete") {
                        art.fetcher.clear()
                        art.clear()
                        ui.pop()
                        screen.refresh()
                        ui.flash("Covers Deleted")
                    })
                }))
                add(Row("Covers come from Deezer and iTunes.", selectable = false))
                add(Row("Only album and artist names are sent.", selectable = false))
            }
        }) {
            private var job: kotlinx.coroutines.Job? = null
            override fun onShown() {
                super.onShown()
                job?.cancel()
                job = ui.app.scope.launch { art.batch.collect { refresh() } }
            }
            override fun onHidden() { job?.cancel(); job = null }
        }
        return screen
    }

    private fun deviceColour(ui: Ui): MenuScreen {
        lateinit var screen: MenuScreen
        screen = MenuScreen(ui, "Device Colour") {
            Skins.all.map { s ->
                Row(s.label, checked = s.id == ui.app.prefs.skin, onSelect = {
                    ui.app.prefs.skin = s.id
                    ui.settingsChanged()
                    screen.refresh()
                })
            }
        }
        return screen
    }

    fun homeScreen(ui: Ui): MenuScreen {
        lateinit var screen: MenuScreen
        screen = object : MenuScreen(ui, "Home Screen", build = {
            val isHome = HomeRole.isDefaultHome(ui.activity)
            buildList {
                add(Row(if (isHome) "Wheelhouse is your home screen" else "Wheelhouse is not your home screen", selectable = false))
                if (!isHome) add(Row("Make Wheelhouse Home", onSelect = { HomeRole.request(ui.activity) }))
                add(Row(if (isHome) "Switch to Another Home App…" else "Choose Home App…", chevron = true,
                    onSelect = { HomeRole.openHomeSettings(ui.activity) }))
                add(Row("Tip: the Home button always", selectable = false))
                add(Row("returns to the Main Menu.", selectable = false))
            }
        }) {
            override fun onShown() = refresh()
        }
        return screen
    }

    private fun about(ui: Ui) = MenuScreen(ui, "About") {
        val idx = ui.app.library.index.value
        val stat = runCatching { StatFs(Environment.getExternalStorageDirectory().path) }.getOrNull()
        fun gb(b: Long) = String.format(Locale.ROOT, "%.1f GB", b / 1_073_741_824.0)
        listOfNotNull(
            Row("Wheelhouse", detail = BuildConfig.VERSION_NAME),
            Row("Songs", detail = idx.songs.size.toString()),
            Row("Albums", detail = idx.albums.size.toString()),
            Row("Artists", detail = idx.artists.size.toString()),
            Row("Playlists", detail = ui.app.library.playlists.size.toString()),
            stat?.let { Row("Capacity", detail = gb(it.totalBytes)) },
            stat?.let { Row("Available", detail = gb(it.availableBytes)) },
            Row("Model", detail = Build.MODEL),
            Row("Android", detail = Build.VERSION.RELEASE),
            Row("Licence", detail = "MIT"),
            Row("github.com/pronzzz/Wheelhouse", selectable = false),
        )
    }

    fun confirm(ui: Ui, question: String, action: String, onConfirm: () -> Unit) = MenuScreen(ui, "Confirm") {
        listOf(
            Row(question, selectable = false),
            Row(action, onSelect = onConfirm),
            Row("Cancel", onSelect = { ui.pop() }),
        )
    }

    // ---- First run ----------------------------------------------------------------------------

    fun welcome(ui: Ui, permanentlyDenied: Boolean, onAllow: () -> Unit) = MenuScreen(ui, "Welcome") {
        listOf(
            Row("Welcome to Wheelhouse", selectable = false),
            Row("Turn this phone into a music player.", selectable = false),
            if (permanentlyDenied) Row("Open App Settings", chevron = true, onSelect = { HomeRole.openAppDetails(ui.activity) })
            else Row("Allow Music Access", chevron = true, onSelect = onAllow),
            Row("Drag around the wheel to scroll,", selectable = false),
            Row("press the centre button to choose.", selectable = false),
        )
    }

    fun homePrompt(ui: Ui, onDone: () -> Unit) = MenuScreen(ui, "Home Screen") {
        listOf(
            Row("Use Wheelhouse as your home screen?", selectable = false),
            Row("The phone will start straight into music.", selectable = false),
            Row("Set as Home Screen", onSelect = { HomeRole.request(ui.activity); onDone() }),
            Row("Not Now", onSelect = onDone),
            Row("You can change this in Settings.", selectable = false),
        )
    }
}
