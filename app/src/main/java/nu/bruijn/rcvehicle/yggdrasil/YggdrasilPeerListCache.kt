package nu.bruijn.rcvehicle.yggdrasil

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun cacheFile(context: Context) = File(context.filesDir, CACHE_FILE_NAME)

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
            Cache(
                fetchedAtMs = json.getLong("fetchedAtMs"),
                peers = (0 until array.length()).map { array.getString(it) },
            )
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
            if (fetched.isNotEmpty()) writeCache(appContext, fetched)
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
                        peers.add(match.value.trim('`'))
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
