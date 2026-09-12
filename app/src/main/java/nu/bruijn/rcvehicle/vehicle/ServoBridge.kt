package nu.bruijn.rcvehicle.vehicle

import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Talks to the ESP32's Joystick Mode (plan section 5): GET /joystick/on once to force
 * the board into Servotester_Menu + webJoystickMode (webInterface.h:415-420), then
 * PosJ{ch}=<value> over the port-81 websocket. Values are raw microseconds, NOT a
 * percentage - confirmed by reading applySteerOutput/applyThrottleOutput and
 * servoModes.h directly, not assumed.
 *
 * Channel numbers and calibration are whatever that specific board's own Settings
 * menu has configured (JOYSTICK_X_CHANNEL/JOYSTICK_Y_CHANNEL/servo range) - defaults
 * here match the test unit currently wired up (steer=ch3, throttle=ch4, standard
 * 1000/1500/2000us range, confirmed working 2026-09-12).
 */
class ServoBridge(
    private val steerChannel: Int = 3,
    private val throttleChannel: Int = 4,
    private val centerUs: Int = 1500,
    private val minUs: Int = 1000,
    private val maxUs: Int = 2000,
    private val failsafeTimeoutMs: Long = 300,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var lastControlAtMs = 0L
    private var watchdogJob: Job? = null

    var onStateChange: ((String) -> Unit)? = null

    fun start(network: Network, espIp: String) {
        stop() // clean up any previous connection first

        val okHttpClient = OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
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

    /** steer/throttle normalized to -1.0..1.0. */
    fun sendControl(steer: Float, throttle: Float) {
        lastControlAtMs = System.currentTimeMillis()
        send(steerChannel, toUs(steer))
        send(throttleChannel, toUs(throttle))
    }

    private fun toUs(value: Float): Int {
        val clamped = value.coerceIn(-1f, 1f)
        return if (clamped >= 0) {
            (centerUs + clamped * (maxUs - centerUs)).toInt()
        } else {
            (centerUs + clamped * (centerUs - minUs)).toInt()
        }
    }

    private fun send(channel: Int, us: Int) {
        webSocket?.send("PosJ$channel=$us")
    }

    private fun sendNeutral() {
        send(steerChannel, centerUs)
        send(throttleChannel, centerUs)
    }

    // App-side failsafe, independent of the firmware's own >500ms one (v1.15): a
    // dropped RTC data channel or a UI stuck open must not leave a stale command
    // applied any longer than necessary. Keeps resending neutral at 10Hz until
    // sendControl() is called again with fresh input.
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(100)
                if (lastControlAtMs != 0L &&
                    System.currentTimeMillis() - lastControlAtMs > failsafeTimeoutMs
                ) {
                    sendNeutral()
                }
            }
        }
    }

    fun stop() {
        sendNeutral()
        watchdogJob?.cancel()
        webSocket?.close(1000, "stopping")
        webSocket = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
        lastControlAtMs = 0L
    }
}
