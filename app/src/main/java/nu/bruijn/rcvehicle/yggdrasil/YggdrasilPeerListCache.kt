package nu.bruijn.rcvehicle.yggdrasil

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Refreshes YggdrasilBootstrapPeers' snapshot from the live
 * github.com/yggdrasil-network/public-peers repo at most once a month - see
 * that class's doc for why the baked-in snapshot exists as a fallback (never
 * fetched yet, or the fetch failed) instead of this being the only source.
 *
 * Deliberately doesn't pull the ENTIRE repo (several hundred peers across
 * every country) - PeerMonitor probes every candidate every cycle, in
 * parallel, so a pool that size would mean hundreds of TCP-connect attempts
 * every 5-60s for negligible benefit over a well-spread curated set of one
 * representative country file per continent-ish region (same set
 * YggdrasilBootstrapPeers' own snapshot was hand-picked from).
 *
 * The fetch is fire-and-forget from a plain background thread, never
 * blocking [get] - a caller always gets *some* list back immediately (the
 * cache file if one exists, however old, else the baked-in snapshot), and
 * only benefits from a fresher one on its next call once a background
 * refresh (if one was due) finishes.
 */
object YggdrasilPeerListCache {
    private const val TAG = "YggdrasilPeerListCache"
    private const val CACHE_FILE_NAME = "yggdrasil-peers-cache.json"
    private const val REFRESH_INTERVAL_MS = 30L * 24 * 60 * 60 * 1000 // ~monthly

    private val SOURCE_FILES = listOf(
        "europe/netherlands", "europe/germany", "europe/france", "europe/united-kingdom",
        "europe/sweden", "europe/poland", "europe/spain", "europe/switzerland",
        "north-america/united-states", "north-america/canada",
        "south-america/brazil",
        "asia/japan", "asia/india",
        "oceania/australia",
        "africa/south-africa",
    )
    private const val RAW_BASE_URL = "https://raw.githubusercontent.com/yggdrasil-network/public-peers/master"

    // tcp:// / tls:// only - see YggdrasilBootstrapPeers' doc for why quic/ws
    // entries in these files are skipped (PeerLatencyProbe can't measure them).
    private val PEER_LINE_REGEX = Regex("`(tcp|tls)://[^`]+`")

    // Confirmed on real hardware as a real demo-blocking bug without this: a
    // malformed or garbage entry that slipped through the regex (a GitHub
    // markdown format change, an encoding quirk, anything) got cached and
    // then retried forever, every cycle, indistinguishable in the UI from a
    // genuinely offline peer - nothing short of clearing the app's data ever
    // recovered, since the bad entry just kept getting reloaded from disk on
    // every launch. A fetch is only trusted enough to overwrite the existing
    // cache if most of what it found actually parses as a real peer URI, and
    // any individual entry that doesn't is dropped rather than poisoning the
    // whole pool.
    private const val MIN_VALID_PEERS_TO_ACCEPT_FETCH = 5

    private fun isValidPeerUri(uri: String): Boolean {
        val parsed = runCatching { URI(uri) }.getOrNull() ?: return false
        return parsed.scheme in setOf("tcp", "tls") && parsed.host != null && parsed.port in 1..65535
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun cacheFile(context: Context) = File(context.filesDir, CACHE_FILE_NAME)

    /** Deletes the on-disk cache, if any, so the next [get] falls back to
     * YggdrasilBootstrapPeers' known-good baked-in snapshot instead - used by
     * PeerMonitor when every candidate in the current pool has been
     * unreachable for long enough that the pool itself, not just bad luck, is
     * the likely explanation (see its own doc). Doesn't touch the CURRENTLY
     * running session's already-fixed candidate list (that's immutable for
     * this service instance's lifetime) - only the next connect attempt
     * benefits, whether that's this session's own Retry button or a fresh
     * app start. */
    fun invalidate(context: Context) {
        runCatching { cacheFile(context).delete() }
    }

    /** The current best-known candidate pool - see class doc for freshness/
     * blocking behavior. */
    fun get(context: Context): List<String> {
        val cached = readCache(context)
        refreshIfStale(context, cached?.fetchedAtMs ?: 0L)
        return cached?.peers ?: YggdrasilBootstrapPeers.CANDIDATES
    }

    private data class Cache(val fetchedAtMs: Long, val peers: List<String>)

    private fun readCache(context: Context): Cache? {
        val file = cacheFile(context)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val array = json.getJSONArray("peers")
            val peers = (0 until array.length()).map { array.getString(it) }.filter { isValidPeerUri(it) }
            // Re-validated on every read, not just at write time - protects
            // against a cache already written to disk by an older version of
            // this class before this validation existed (see its own doc),
            // not just future writes. Same "don't trust a suspiciously small/
            // degraded result" floor as a fresh fetch.
            if (peers.size < MIN_VALID_PEERS_TO_ACCEPT_FETCH) {
                Log.i(TAG, "peer cache has too few valid entries (${peers.size}), discarding")
                return null
            }
            Cache(fetchedAtMs = json.getLong("fetchedAtMs"), peers = peers)
        } catch (e: Exception) {
            Log.i(TAG, "reading peer cache failed: $e")
            null
        }
    }

    private fun refreshIfStale(context: Context, lastFetchedAtMs: Long) {
        if (System.currentTimeMillis() - lastFetchedAtMs < REFRESH_INTERVAL_MS) return
        val appContext = context.applicationContext
        thread(name = "YggdrasilPeerListFetch", isDaemon = true) {
            val fetched = fetchLive()
            // Below the sanity floor reads as "something went wrong with the
            // fetch itself" (a format change, a bad response, a half-broken
            // network) rather than "these are genuinely all the peers there
            // are" - leaves the existing cache (or the baked-in snapshot, if
            // there was none yet) in place rather than risking a near-empty
            // or otherwise-degraded pool replacing a working one.
            if (fetched.size >= MIN_VALID_PEERS_TO_ACCEPT_FETCH) writeCache(appContext, fetched)
        }
    }

    private fun fetchLive(): List<String> {
        val peers = LinkedHashSet<String>()
        for (path in SOURCE_FILES) {
            try {
                val request = Request.Builder().url("$RAW_BASE_URL/$path.md").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: return@use
                    PEER_LINE_REGEX.findAll(body).forEach { match ->
                        val uri = match.value.trim('`')
                        if (isValidPeerUri(uri)) peers.add(uri)
                    }
                }
            } catch (e: Exception) {
                // Best-effort per file - one bad/renamed/offline file shouldn't
                // block the rest of the list from updating.
                Log.i(TAG, "fetching $path failed: $e")
            }
        }
        return peers.toList()
    }

    private fun writeCache(context: Context, peers: List<String>) {
        try {
            val json = JSONObject().apply {
                put("fetchedAtMs", System.currentTimeMillis())
                put("peers", JSONArray(peers))
            }
            cacheFile(context).writeText(json.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.i(TAG, "writing peer cache failed: $e")
        }
    }
}
