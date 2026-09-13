package nu.bruijn.rcvehicle.vehicle

import android.net.Network
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Checks GitHub Releases for a newer ESP32 firmware build and downloads it - the
 * phone-side half of updating over USB (see UsbServoBridge.checkFirmwareVersion()/
 * flashFirmware() for the other half, and the firmware's own firmwareUpdate.h for
 * why this is needed at all: the board's own GitHub-based update check requires
 * wifi+internet, which the USB-only primary control path never has). Same repo
 * and "firmware.bin" asset name the board's own checkForFirmwareUpdate() already
 * uses, so a release built for one update path works for both.
 *
 * No JSON library needed for two flat string fields - same reasoning as the
 * firmware's own jsonStringField() helper.
 *
 * [network], when given, forces these calls onto a specific Network (see
 * InternetNetworkBinder) instead of the phone's current default route - confirmed
 * on real hardware as necessary: once the phone has joined the ESP32's own
 * (no-internet) AP for the wifi-based update path below, that network can silently
 * become the default route, and a plain unbound client just fails reaching GitHub
 * from inside that same network with no obvious reason why.
 */
object FirmwareUpdater {
    private const val RELEASES_API_URL =
        "https://api.github.com/repos/MichielBruijn/esp32-servo-tester/releases/latest"
    private const val ASSET_NAME = "firmware.bin"

    data class Release(val version: String, val downloadUrl: String)

    private fun client(network: Network?): OkHttpClient =
        OkHttpClient.Builder().apply { network?.let { socketFactory(it.socketFactory) } }.build()

    /** Blocking - call from a background thread/coroutine. Returns null on any failure. */
    fun fetchLatestRelease(network: Network? = null): Release? {
        val request = Request.Builder()
            .url(RELEASES_API_URL)
            .header("User-Agent", "esp32-rc-fpv-control") // GitHub's API rejects requests with no User-Agent
            .build()
        return try {
            client(network).newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val tag = jsonStringField(body, "tag_name")?.removePrefix("v")?.removePrefix("V") ?: return null
                val nameIdx = body.indexOf("\"name\":\"$ASSET_NAME\"")
                if (nameIdx < 0) return null
                val downloadUrl = jsonStringField(body, "browser_download_url", nameIdx) ?: return null
                Release(tag, downloadUrl)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Blocking - call from a background thread/coroutine. Returns null on any failure. */
    fun download(url: String, network: Network? = null): ByteArray? {
        val request = Request.Builder().url(url).build()
        return try {
            client(network).newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.bytes()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** True if [remote] (e.g. "1.25") is a newer version string than [current]. */
    fun isNewer(remote: String, current: String): Boolean =
        (remote.toFloatOrNull() ?: 0f) > (current.toFloatOrNull() ?: 0f)

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
