package nu.bruijn.rcvehicle.vehicle

import android.net.Network
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Checks GitHub Releases for a newer ESP32 firmware build and downloads it - see
 * VehicleActivity.setUpFirmwareUpdateUi() for the version-check (any link,
 * ServoLinkCommands.sendCommand("GETVERSION")) and install (WifiFirmwareUpdater,
 * over the board's own AP) halves, and the firmware's own firmwareUpdate.h for why
 * this download step exists at all: the board's own GitHub-based update check
 * requires wifi+internet, which it never has while running as its own AP. Same
 * repo and "firmware.bin" asset name the board's own checkForFirmwareUpdate()
 * already uses, so a release built for one update path works for both.
 *
 * No JSON library needed for two flat string fields - same reasoning as the
 * firmware's own jsonStringField() helper.
 *
 * The repo is picked per [Board] - both are public so no GitHub token is needed in
 * the APK. Never mix them up: a real-hardware test caught the wrong architecture's binary
 * (classic board, Xtensa) uploaded over wifi byte-for-byte successfully, then
 * Update.end() correctly rejected it as an invalid image on this RISC-V chip -
 * "Write failed: N/N bytes, end=0, ...".
 *
 * [network], when given, forces these calls onto a specific Network (see
 * InternetNetworkBinder) instead of the phone's current default route - confirmed
 * on real hardware as necessary: once the phone has joined the ESP32's own
 * (no-internet) AP for the wifi-based update path below, that network can silently
 * become the default route, and a plain unbound client just fails reaching GitHub
 * from inside that same network with no obvious reason why.
 */
object FirmwareUpdater {
    private const val ASSET_NAME = "firmware.bin"

    /** Which board is on the other end - picked from its own GETVERSION reply, since both
     * speak the same protocol but need their own architecture's binary and AP name. */
    enum class Board(val repo: String, val apSsid: String) {
        C3_MINI("MichielBruijn/esp32-c3-servo-controller", "MespDrive"),
        CLASSIC("MichielBruijn/esp32-servo-tester", "ServoTester");

        companion object {
            // C3 codeVersion always carries "-c3mini" ("0.2-c3mini"); classic is plain ("1.39").
            fun forVersion(version: String): Board =
                if (version.contains("c3", ignoreCase = true)) C3_MINI else CLASSIC
        }
    }

    data class Release(val version: String, val downloadUrl: String, val board: Board)

    private fun client(network: Network?): OkHttpClient =
        OkHttpClient.Builder().apply { network?.let { socketFactory(it.socketFactory) } }.build()

    /** Blocking - call from a background thread/coroutine. Returns null on any failure. */
    fun fetchLatestRelease(board: Board, network: Network? = null): Release? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${board.repo}/releases/latest")
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
                Release(tag, downloadUrl, board)
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

    // Plain toFloatOrNull() is strict - it returns null (not a best-effort parse) for anything
    // with a trailing non-numeric suffix, which this board's own codeVersion always has
    // ("0.2-c3mini"). Confirmed on real hardware as a genuine bug: both sides silently fell
    // back to 0f and isNewer() always returned false - "up to date" no matter what actually
    // released. Extract just the leading numeric prefix instead.
    private val leadingNumber = Regex("""^[0-9]+(\.[0-9]+)?""")

    private fun leadingVersionNumber(version: String): Float =
        leadingNumber.find(version)?.value?.toFloatOrNull() ?: 0f

    /** True if [remote] (e.g. "0.2-c3mini") is a newer version string than [current]. */
    fun isNewer(remote: String, current: String): Boolean =
        leadingVersionNumber(remote) > leadingVersionNumber(current)

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
