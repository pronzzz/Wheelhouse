package io.github.pronzzz.wheelhouse.spool

import android.content.Context
import android.net.ConnectivityManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException

/** A Spool engine that answered on the local network. */
data class FoundSpool(val name: String, val host: String, val port: Int, val version: String)

/**
 * Finds computers running `spool --lan` by broadcasting a short question on the Wi-Fi
 * network. Each engine answers with its name and port. Blocking: call off the main thread.
 */
object SpoolDiscovery {
    const val PORT = 7317
    private const val MESSAGE = "SPOOL-DISCOVER-1"

    fun find(context: Context, waitMs: Int = 2_500): List<FoundSpool> {
        val targets = broadcastAddresses(context) + InetAddress.getByName("255.255.255.255")
        val found = LinkedHashMap<String, FoundSpool>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 400
            val msg = MESSAGE.toByteArray()
            // Ask twice: the first packet on a sleepy Wi-Fi radio is sometimes lost.
            repeat(2) {
                for (t in targets.distinct()) runCatching { socket.send(DatagramPacket(msg, msg.size, t, PORT)) }
                val until = System.currentTimeMillis() + waitMs / 2
                val buf = ByteArray(1024)
                while (System.currentTimeMillis() < until) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(p)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    parse(String(p.data, 0, p.length), p.address.hostAddress ?: continue)?.let { found[it.host + ":" + it.port] = it }
                }
            }
        }
        return found.values.toList()
    }

    fun parse(reply: String, from: String): FoundSpool? = runCatching {
        val j = JSONObject(reply)
        if (j.optString("app") != "spool") return null
        FoundSpool(j.optString("name", from).ifBlank { from }, from, j.optInt("port", PORT), j.optString("version"))
    }.getOrNull()

    /** The directed broadcast address of each IPv4 network the phone is on (e.g. 192.168.1.255). */
    private fun broadcastAddresses(context: Context): List<InetAddress> = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        cm.allNetworks.flatMap { n -> cm.getLinkProperties(n)?.linkAddresses.orEmpty() }
            .mapNotNull { la ->
                val a = la.address as? Inet4Address ?: return@mapNotNull null
                if (a.isLoopbackAddress) return@mapNotNull null
                val ip = a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
                val mask = if (la.prefixLength == 0) 0 else -1 shl (32 - la.prefixLength)
                val bc = ip or mask.inv()
                InetAddress.getByAddress(byteArrayOf((bc ushr 24).toByte(), (bc ushr 16).toByte(), (bc ushr 8).toByte(), bc.toByte()))
            }
    }.getOrDefault(emptyList())
}
