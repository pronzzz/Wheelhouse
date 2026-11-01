package io.github.pronzzz.wheelhouse

import android.app.Application
import io.github.pronzzz.wheelhouse.data.ArtworkFetcher
import io.github.pronzzz.wheelhouse.data.ArtworkLoader
import io.github.pronzzz.wheelhouse.data.Library
import io.github.pronzzz.wheelhouse.data.NetworkMonitor
import io.github.pronzzz.wheelhouse.data.Prefs
import io.github.pronzzz.wheelhouse.data.db.WheelhouseDatabase
import io.github.pronzzz.wheelhouse.launcher.AppsRepository
import io.github.pronzzz.wheelhouse.playback.PlayerConnection
import io.github.pronzzz.wheelhouse.spool.SpoolDownloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Holds the app-wide singletons. Kept deliberately simple: no DI framework. */
class WheelhouseApp : Application() {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val prefs by lazy { Prefs(this) }
    val database by lazy { WheelhouseDatabase.create(this) }
    val library by lazy { Library(this, database.dao(), scope) }
    val network by lazy { NetworkMonitor(this) }
    val artwork by lazy { ArtworkLoader(this, ArtworkFetcher(this, prefs, network)) }
    val player by lazy { PlayerConnection(this, library, prefs, scope) }
    val apps by lazy { AppsRepository(this) }
    val spool by lazy { SpoolDownloads(this, prefs) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Picks up downloads that were still running when the app last closed.
        if (prefs.spoolHost.isNotBlank()) spool
        // Coming online: albums that had no cover get another chance at an online lookup.
        scope.launch {
            network.state.map { it.online }.distinctUntilChanged().drop(1).filter { it }.collect { artwork.retryMissing() }
        }
    }

    companion object {
        lateinit var instance: WheelhouseApp
            private set
    }
}
