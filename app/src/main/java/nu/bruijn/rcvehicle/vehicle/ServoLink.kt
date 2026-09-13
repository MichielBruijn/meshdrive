package nu.bruijn.rcvehicle.vehicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Shared -1.0..1.0 -> microseconds mapping and failsafe-watchdog logic for the two
 * ways of reaching the ESP32's Joystick Mode: wifi/websocket (ServoBridge) and
 * USB-OTG serial (UsbServoBridge, added 2026-09-14 as the primary path - avoids
 * the vehicle phone having to juggle wifi-to-ESP32 plus its own mobile-data
 * uplink for Yggdrasil at the same time; wifi is kept as a fallback option).
 * Only `send(channel, us)` - how the two raw PosJ writes actually reach the board -
 * differs between the two transports.
 */
abstract class ServoLink(
    private val steerChannel: Int,
    private val throttleChannel: Int,
    private val centerUs: Int,
    private val minUs: Int,
    private val maxUs: Int,
    private val failsafeTimeoutMs: Long,
) {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastControlAtMs = 0L
    private var watchdogJob: Job? = null

    var onStateChange: ((String) -> Unit)? = null

    /** Write one channel's raw microsecond value ("PosJ{channel}={us}") to the board. */
    protected abstract fun send(channel: Int, us: Int)

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

    protected fun sendNeutral() {
        send(steerChannel, centerUs)
        send(throttleChannel, centerUs)
    }

    // App-side failsafe, independent of the firmware's own >500ms one (v1.15): a
    // dropped link or a UI stuck open must not leave a stale command applied any
    // longer than necessary. Keeps resending neutral at 10Hz until sendControl()
    // is called again with fresh input.
    protected fun startWatchdog() {
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

    protected fun stopWatchdog() {
        watchdogJob?.cancel()
    }

    open fun stop() {
        sendNeutral()
        stopWatchdog()
        lastControlAtMs = 0L
    }
}
