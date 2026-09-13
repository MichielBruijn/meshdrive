package nu.bruijn.rcvehicle.vehicle

import android.net.Network
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Flashes firmware onto the ESP32 over its own wifi AP, using the board's existing
 * "POST /uploadfirmware/on" endpoint (webInterface.h/firmwareUpdate.h's
 * uploadCurrentFirmware()) - a plain TCP upload, which has real error detection
 * built into the transport (retransmission/checksums). This replaces flashing over
 * the raw USB-serial link (UsbServoBridge.flashFirmware()): that link corrupted the
 * transfer on every attempt - confirmed via Update.setMD5() catching an MD5
 * mismatch even after a full, uninterrupted 115200-baud transfer - despite several
 * rounds of protocol/timing fixes (a baud-switch handshake, removing read/write
 * concurrency, purging stale hardware buffers). GETVERSION (checkFirmwareVersion())
 * stays on USB - that's a tiny few-byte round trip, never implicated in the
 * corruption, no reason to also require wifi just to check the current version.
 *
 * Requires the phone to already be joined to the ESP32's wifi network - same
 * EspWifiNetworkBinder-bound-socket approach as ServoBridge's control link, so this
 * doesn't touch the phone's default network (where Yggdrasil-over-cellular keeps
 * running unaffected).
 */
class WifiFirmwareUpdater {
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
            if (fullyWritten) true to "update installed, vehicle rebooting" else false to "upload failed: ${e.message}"
        } catch (e: Exception) {
            false to "upload failed: ${e.message}"
        } finally {
            client.dispatcher.executorService.shutdown()
        }
    }
}
