package nu.bruijn.rcvehicle

import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Checks GitHub Releases for a newer build of this app itself - the same idea as
 * vehicle/FirmwareUpdater.kt (which does this for the ESP32's firmware), applied
 * to MeshDrive's own APK. Unlike that one, no network-binding is needed here:
 * this only ever runs from MainActivity, which never joins a no-internet AP the
 * way the ESP32 update flow does.
 *
 * Doesn't assume a fixed asset filename (FirmwareUpdater can, since every
 * firmware release uses "firmware.bin") - this repo's own releases so far used
 * version-specific names (MeshDrive-v0.2.apk, MeshDrive-v0.3.apk), so this just
 * takes the first asset ending in ".apk" instead.
 */
object AppUpdater {
    private const val RELEASES_API_URL =
        "https://api.github.com/repos/MichielBruijn/meshdrive/releases/latest"

    data class Release(val version: String, val downloadUrl: String)

    private val client = OkHttpClient()

    /** Blocking - call from a background thread/coroutine. Returns null on any failure. */
    fun fetchLatestRelease(): Release? {
        val request = Request.Builder()
            .url(RELEASES_API_URL)
            .header("User-Agent", "meshdrive") // GitHub's API rejects requests with no User-Agent
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val tag = jsonStringField(body, "tag_name")?.removePrefix("v")?.removePrefix("V") ?: return null
                val nameIdx = findApkAssetNameIndex(body) ?: return null
                val downloadUrl = jsonStringField(body, "browser_download_url", nameIdx) ?: return null
                Release(tag, downloadUrl)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Blocking - call from a background thread/coroutine. Returns null on any failure. */
    fun download(url: String): ByteArray? {
        val request = Request.Builder().url(url).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.bytes()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** True if [remote] (e.g. "0.3") is a newer version string than [current]. */
    fun isNewer(remote: String, current: String): Boolean =
        (remote.toFloatOrNull() ?: 0f) > (current.toFloatOrNull() ?: 0f)

    /** Finds the `"name":"...apk"` field of the first .apk asset, returning the
     * index right before it - a starting point for jsonStringField() to then find
     * that same asset's browser_download_url just after it in the same object. */
    private fun findApkAssetNameIndex(json: String): Int? {
        var searchFrom = 0
        while (true) {
            val idx = json.indexOf("\"name\":\"", searchFrom)
            if (idx < 0) return null
            val valueStart = idx + 8
            val valueEnd = json.indexOf('"', valueStart)
            if (valueEnd < 0) return null
            if (json.substring(valueStart, valueEnd).endsWith(".apk")) return idx
            searchFrom = valueEnd + 1
        }
    }

    private fun jsonStringField(json: String, key: String, searchFrom: Int = 0): String? {
        val needle = "\"$key\":\""
        val keyIdx = json.indexOf(needle, searchFrom)
        if (keyIdx < 0) return null
        val valueStart = keyIdx + needle.length
        val valueEnd = json.indexOf('"', valueStart)
        if (valueEnd < 0) return null
        return json.substring(valueStart, valueEnd)
    }
}
