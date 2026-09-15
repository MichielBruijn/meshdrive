package nu.bruijn.rcvehicle.yggdrasil

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * Ranks candidate Yggdrasil peers by a cheap reachability/latency proxy - a raw
 * TCP-connect time - so YggdrasilVpnService can auto-pick the best one without
 * actually joining the mesh through each candidate first. Doesn't validate the
 * TLS or Yggdrasil-protocol handshake itself (a host could accept the TCP
 * connection and still fail one level up) - a deliberate trade-off: catching
 * that rarer case isn't worth the cost of a real Yggdrasil connect attempt per
 * candidate, every monitor cycle, for every candidate in the pool.
 */
object PeerLatencyProbe {
    data class Status(val uri: String, val latencyMs: Long?, val checkedAtMs: Long = System.currentTimeMillis())

    private const val CONNECT_TIMEOUT_MS = 3000

    /** null latency means unreachable (timed out, refused, or an unparsable/
     * unprobeable URI - quic:// peers are UDP-based and can't be measured this
     * way, so they're reported as unreachable rather than guessed at). */
    fun measure(peerUri: String): Status {
        val parsed = runCatching { URI(peerUri) }.getOrNull()
        val host = parsed?.host
        val port = parsed?.port ?: -1
        if (parsed == null || host == null || port !in 1..65535 || parsed.scheme !in setOf("tcp", "tls")) {
            return Status(peerUri, null)
        }
        val start = System.nanoTime()
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
            Status(peerUri, (System.nanoTime() - start) / 1_000_000)
        } catch (_: Exception) {
            Status(peerUri, null)
        }
    }

    /** Probes every candidate in parallel (plain threads, not coroutines - this
     * runs from a background service thread, not a coroutine scope) so a bad
     * candidate's timeout doesn't stack onto the next one's. */
    fun measureAll(peerUris: List<String>): List<Status> {
        if (peerUris.isEmpty()) return emptyList()
        val results = arrayOfNulls<Status>(peerUris.size)
        val threads = peerUris.mapIndexed { i, uri -> Thread { results[i] = measure(uri) }.apply { start() } }
        threads.forEach { it.join(CONNECT_TIMEOUT_MS + 500L) }
        return peerUris.mapIndexed { i, uri -> results[i] ?: Status(uri, null) }
    }
}
