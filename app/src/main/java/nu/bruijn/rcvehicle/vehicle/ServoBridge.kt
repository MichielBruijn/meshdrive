package nu.bruijn.rcvehicle.vehicle

import android.net.Network
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Wraps a SocketFactory to force TCP_NODELAY on every socket it creates. Without
 * this, Nagle's algorithm batches our small, frequent PosJ frames and interacts
 * with the ESP32's delayed ACKs, adding ~40-200ms of irregular stall - invisible
 * while holding a steady value, but very visible as stutter while a value is
 * actively changing (i.e. exactly "jerky while dragging, smooth at rest", which is
 * what gave this away). Browsers set TCP_NODELAY on websockets automatically;
 * OkHttp does not, so it has to be done explicitly here.
 */
private class NoDelaySocketFactory(private val delegate: SocketFactory) : SocketFactory() {
    private fun Socket.noDelay() = apply { tcpNoDelay = true }

    override fun createSocket() = delegate.createSocket().noDelay()
    override fun createSocket(host: String?, port: Int) =
        delegate.createSocket(host, port).noDelay()
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int) =
        delegate.createSocket(host, port, localHost, localPort).noDelay()
    override fun createSocket(host: InetAddress?, port: Int) =
        delegate.createSocket(host, port).noDelay()
    override fun createSocket(
        address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int
    ) = delegate.createSocket(address, port, localAddress, localPort).noDelay()
}

/**
 * Talks to the ESP32's Joystick Mode over its own wifi AP: GET /joystick/on once to
 * force the board into Servotester_Menu + webJoystickMode (webInterface.h:415-420),
 * then PosJ{ch}=<value> over the port-81 websocket. Values are raw microseconds,
 * NOT a percentage - confirmed by reading applySteerOutput/applyThrottleOutput and
 * servoModes.h directly, not assumed.
 *
 * Fallback control path since 2026-09-14 - UsbServoBridge (USB-OTG serial) is now
 * the primary one, avoiding this bridge's underlying problem: the vehicle phone
 * has to keep both wifi-to-ESP32 and its own mobile-data-for-Yggdrasil uplink alive
 * at once, which real-world testing found Android doesn't always route reliably
 * (see EspWifiNetworkBinder's doc / project memory). Kept as an alternative for
 * whenever wifi is more convenient than a cable.
 *
 * Channel numbers and calibration are whatever that specific board's own Settings
 * menu has configured (JOYSTICK_X_CHANNEL/JOYSTICK_Y_CHANNEL/servo range) - defaults
 * here match the test unit currently wired up (steer=ch3, throttle=ch4, standard
 * 1000/1500/2000us range, confirmed working 2026-09-12).
 */
class ServoBridge(
    steerChannel: Int = 3,
    throttleChannel: Int = 4,
    centerUs: Int = 1500,
    minUs: Int = 1000,
    maxUs: Int = 2000,
    failsafeTimeoutMs: Long = 300,
) : ServoLink(steerChannel, throttleChannel, centerUs, minUs, maxUs, failsafeTimeoutMs) {
    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null

    fun start(network: Network, espIp: String) {
        stop() // clean up any previous connection first

        val okHttpClient = OkHttpClient.Builder()
            .socketFactory(NoDelaySocketFactory(network.socketFactory))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout - this is a long-lived websocket
            .build()
        client = okHttpClient

        scope.launch {
            try {
                val req = Request.Builder().url("http://$espIp/joystick/on").build()
                okHttpClient.newCall(req).execute().close()
                onStateChange?.invoke("joystick/on OK - opening websocket")
            } catch (e: Exception) {
                onStateChange?.invoke("joystick/on failed: ${e.message}")
            }

            val wsRequest = Request.Builder().url("ws://$espIp:81/").build()
            webSocket = okHttpClient.newWebSocket(wsRequest, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    onStateChange?.invoke("websocket open")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    onStateChange?.invoke("websocket failed: ${t.message}")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    onStateChange?.invoke("websocket closed: $reason")
                }
            })
        }

        startWatchdog()
    }

    override fun send(channel: Int, us: Int) {
        webSocket?.send("PosJ$channel=$us")
    }

    override fun stop() {
        super.stop()
        webSocket?.close(1000, "stopping")
        webSocket = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
    }
}
