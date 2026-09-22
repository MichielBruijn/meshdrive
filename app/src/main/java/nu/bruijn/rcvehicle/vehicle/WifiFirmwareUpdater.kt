package nu.bruijn.rcvehicle.vehicle

import android.net.Network
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Flashes firmware onto the ESP32 over its own wifi AP ("MespDrive"), using the
 * board's existing "POST /uploadfirmware/on" endpoint (webInterface.h/
 * firmwareUpdate.h's uploadCurrentFirmware()) - a plain TCP upload, which has real
 * error detection built into the transport (retransmission/checksums).
 *
 * Both USB-serial approaches tried before this (the app's own OTAUPDATE chunk/ACK
 * protocol, and later a full esp-serial-flasher/esptool ROM-bootloader
 * reimplementation over USB-OTG) proved unreliable on this board's native USB-CDC
 * from Android specifically - confirmed across multiple phones and reset methods,
 * while the exact same bytes/protocol worked instantly from a laptop. This wifi
 * path sidesteps USB entirely for the install step.
 *
 * [data] must already be fully downloaded before joining the ESP32's AP - see
 * VehicleActivity.setUpFirmwareUpdateUi(): "Check for update" downloads the
 * release immediately once a newer version is found, specifically so the AP-join
 * step below (which has no internet of its own) never needs it.
 *
 * Requires the phone to already be joined to the ESP32's wifi network - same
 * EspWifiNetworkBinder-bound-socket approach as ServoBridge's control link, so this
 * doesn't touch the phone's default network (where Yggdrasil-over-cellular keeps
 * running unaffected).
 */
class WifiFirmwareUpdater {
    // Plain e.message on some of these (esp. raw Socket/okio errno exceptions) is a terse
    // fragment like "write failed" with the actually-useful part (ECONNRESET, ETIMEDOUT, ...)
    // buried in the cause - surface the whole chain instead of guessing which layer matters.
    private fun describe(e: Throwable): String {
        val parts = generateSequence(e) { it.cause }.map { "${it::class.simpleName}: ${it.message}" }
        return parts.joinToString(" <- ")
    }

    fun upload(
        network: Network,
        espIp: String,
        data: ByteArray,
        onProgress: (written: Int, total: Int) -> Unit,
    ): Pair<Boolean, String> {
        val client = OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        var fullyWritten = false
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = data.size.toLong()
            override fun writeTo(sink: BufferedSink) {
                val chunkSize = 8192
                var offset = 0
                while (offset < data.size) {
                    val end = minOf(offset + chunkSize, data.size)
                    sink.write(data, offset, end - offset)
                    offset = end
                    onProgress(offset, data.size)
                }
                fullyWritten = true
            }
        }

        val request = Request.Builder()
            .url("http://$espIp/uploadfirmware/on")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val text = response.body?.string()?.trim().orEmpty()
                    if (text == "OK") true to "update installed, vehicle rebooting" else false to text.ifEmpty { "unknown error" }
                } else {
                    false to "HTTP ${response.code}"
                }
            }
        } catch (e: IOException) {
            // uploadCurrentFirmware() restarts the ESP32 *inside* the request handler on
            // success and never sends an HTTP response at all (confirmed in webInterface.h,
            // POST /uploadfirmware/on) - the connection just dies when the board reboots.
            // Only trust that as success if the full body was definitely already sent -
            // otherwise this is indistinguishable from a genuinely dropped upload.
            Log.e("WifiFirmwareUpdater", "upload IOException, fullyWritten=$fullyWritten", e)
            if (fullyWritten) true to "update installed, vehicle rebooting" else false to "upload failed: ${describe(e)}"
        } catch (e: Exception) {
            Log.e("WifiFirmwareUpdater", "upload failed", e)
            false to "upload failed: ${describe(e)}"
        } finally {
            client.dispatcher.executorService.shutdown()
        }
    }
}
