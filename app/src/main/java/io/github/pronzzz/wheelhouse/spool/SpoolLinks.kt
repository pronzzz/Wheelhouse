package io.github.pronzzz.wheelhouse.spool

/** Pulls a YouTube link out of shared or copied text ("Check this out: https://youtu.be/…"). */
object SpoolLinks {
    private val url = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val hosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")

    fun extract(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return url.findAll(text).map { it.value.trimEnd('.', ',', ')', ']', '!', '?') }.firstOrNull { link ->
            val host = link.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':').lowercase()
            host in hosts
        }
    }

    /** "host:port", "host" or "[v6]:port" typed by the user. Returns null if it can't be an address. */
    fun parseAddress(input: String, defaultPort: Int = SpoolDiscovery.PORT): Pair<String, Int>? {
        val s = input.trim().removePrefix("http://").substringBefore('/')
        if (s.isEmpty()) return null
        val (host, port) = when {
            s.startsWith("[") -> s.substringAfter('[').substringBefore(']') to s.substringAfter("]:", "").toIntOrNull()
            s.count { it == ':' } == 1 -> s.substringBefore(':') to s.substringAfter(':').toIntOrNull()
            else -> s to null
        }
        if (host.isBlank() || host.any { it.isWhitespace() }) return null
        val p = port ?: defaultPort
        return if (p in 1..65535) host to p else null
    }
}
