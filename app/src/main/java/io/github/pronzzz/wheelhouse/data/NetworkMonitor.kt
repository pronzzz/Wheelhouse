package io.github.pronzzz.wheelhouse.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the phone's connection can do right now. */
data class NetState(
    /** There is a network and Android has checked that it reaches the internet. */
    val online: Boolean = false,
    /** That network is Wi-Fi (or Ethernet). */
    val wifi: Boolean = false,
    /** Android considers the network metered (mobile data, a hotspot). */
    val metered: Boolean = true,
)

/**
 * Watches the default network. "Online" means Android validated it (its captive-portal check
 * succeeded), so a Wi-Fi network without working internet does not count.
 */
class NetworkMonitor(context: Context) {

    private val app = context.applicationContext
    private val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(NetState())
    val state: StateFlow<NetState> = _state

    init {
        refresh()
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = post()
                    override fun onLost(network: Network) = post()
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = post()
                })
            } else {
                @Suppress("DEPRECATION")
                app.registerReceiver(object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) = post()
                }, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
            }
        } catch (_: Exception) {
            // No network access permission or a broken ROM: we simply stay "offline".
        }
    }

    private fun post() { main.post { refresh() } }

    fun refresh() {
        _state.value = try { read() } catch (_: Exception) { NetState() }
    }

    @Suppress("DEPRECATION")
    private fun read(): NetState {
        if (Build.VERSION.SDK_INT >= 23) {
            val network = cm.activeNetwork ?: return NetState()
            val caps = cm.getNetworkCapabilities(network) ?: return NetState()
            val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            return NetState(online, wifi, cm.isActiveNetworkMetered)
        }
        // Android 5: no validation flag, so a connected network has to do.
        val info = cm.activeNetworkInfo ?: return NetState()
        val wifi = info.type == ConnectivityManager.TYPE_WIFI || info.type == ConnectivityManager.TYPE_ETHERNET
        return NetState(info.isConnected, wifi, cm.isActiveNetworkMetered)
    }
}
