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
 * ways of reaching the ESP32's Joystick Mode: USB-OTG serial (UsbServoBridge,
 * primary) and BLE (BleServoBridge). Only `send(channel, us)` - how the two raw
 * PosJ writes actually reach the board - differs between the two transports.
 */
abstract class ServoLink(
    centerUs: Int,
    minUs: Int,
    maxUs: Int,
    private val failsafeTimeoutMs: Long,
) {
    // Null until setChannels() is called with the board's own real, queried values - per
    // direct request, this app must never guess/hardcode which physical channel steer or
    // throttle is on. sendControl()/sendNeutral() below are no-ops while either is null.
    private var steerChannel: Int? = null
    private var throttleChannel: Int? = null
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastControlAtMs = 0L
    private var watchdogJob: Job? = null

    // Both channels start out sharing the constructor's generic RC default
    // (same as before per-channel calibration existed) - setCalibration()
    // below can then split them apart once the board's own real per-channel
    // values are known.
    private var steerMinUs = minUs
    private var steerCenterUs = centerUs
    private var steerMaxUs = maxUs
    private var throttleMinUs = minUs
    private var throttleCenterUs = centerUs
    private var throttleMaxUs = maxUs

    var onStateChange: ((String) -> Unit)? = null

    // False until both UsbServoBridge and BleServoBridge have actually confirmed the board's
    // real Joystick X/Y channels (their own checkJoystickChannels()/resolveBoardConfig()) -
    // only ever flips true once setChannels() has been called with real values, never as a
    // fallback. Confirmed on real hardware as a real race even before that principle: without
    // gating sendControl() somehow, it could fire on the constructor's old placeholder channels
    // (3/4) before setChannels() overwrote them with the board's actual configured channels,
    // since a remote controller can already be sending control frames the moment
    // onConnected(true) fires, well before the GETJOYCHANNELS reply lands.
    protected var readyToSend = false

    /** Overrides the channel mapping after construction - used once
     * UsbServoBridge has queried the board's own configured Joystick X/Y
     * channels (see its checkJoystickChannels()), so this app drives whichever
     * channels the user actually set up on the servo tester itself, per direct
     * request, instead of a hardcoded assumed default. */
    fun setChannels(steer: Int, throttle: Int) {
        steerChannel = steer
        throttleChannel = throttle
    }

    /**
     * Overrides the µs range per channel after construction - used once
     * UsbServoBridge has queried the board's own calibrated Min/Center/Max
     * for the steer/throttle channels (checkCalibration()). Confirmed on real
     * hardware as a real bug without this: the generic 1000/1500/2000 RC
     * default doesn't match a board calibrated to something else (e.g.
     * 700/1250/1801) - the firmware's own applySteerOutput()/
     * applyThrottleOutput() constrain() to ITS real range regardless, so a
     * mismatched app-side assumption just silently lost whichever end of the
     * range sat outside its hardcoded guess.
     */
    fun setCalibration(steerMin: Int, steerCenter: Int, steerMax: Int, throttleMin: Int, throttleCenter: Int, throttleMax: Int) {
        steerMinUs = steerMin
        steerCenterUs = steerCenter
        steerMaxUs = steerMax
        throttleMinUs = throttleMin
        throttleCenterUs = throttleCenter
        throttleMaxUs = throttleMax
    }

    /** Write one channel's raw microsecond value ("PosJ{channel}={us}") to the board. */
    protected abstract fun send(channel: Int, us: Int)

    /**
     * Quick on/off for the firmware's Steering Limit (reduces Steer deflection as
     * Throttle deflection increases) - mirrors the web UI's checkbox
     * (GET /?SteerLimitOn=) via "SteerLimitOn={0|1}" over whichever transport this
     * is. Immediate, not persisted to EEPROM, doesn't touch the configured
     * strength - same as the firmware's own HTTP handler.
     */
    abstract fun setSteerLimitEnabled(enabled: Boolean)

    /** steer/throttle normalized to -1.0..1.0. */
    fun sendControl(steer: Float, throttle: Float) {
        if (!readyToSend) return
        val sc = steerChannel ?: return
        val tc = throttleChannel ?: return
        lastControlAtMs = System.currentTimeMillis()
        send(sc, toUs(steer, steerCenterUs, steerMinUs, steerMaxUs))
        send(tc, toUs(throttle, throttleCenterUs, throttleMinUs, throttleMaxUs))
    }

    private fun toUs(value: Float, centerUs: Int, minUs: Int, maxUs: Int): Int {
        val clamped = value.coerceIn(-1f, 1f)
        return if (clamped >= 0) {
            (centerUs + clamped * (maxUs - centerUs)).toInt()
        } else {
            (centerUs + clamped * (centerUs - minUs)).toInt()
        }
    }

    protected fun sendNeutral() {
        val sc = steerChannel ?: return
        val tc = throttleChannel ?: return
        send(sc, steerCenterUs)
        send(tc, throttleCenterUs)
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

/** Generic GET/SET command round-trip, implemented by both UsbServoBridge and
 * BleServoBridge. "ERROR:..." resolves Error; otherwise, with [expectPrefix] null, the
 * first line back resolves Ok(line) as-is (callers strip their own prefix, e.g.
 * "VERSION="); with it set, only a line starting with it does - anything else the board
 * prints meanwhile (boot banner right after a reset, "EEPROM written", ...) is skipped
 * instead of being mistaken for the reply. Nothing in time -> Timeout. */
interface ServoLinkCommands {
    suspend fun sendCommand(command: String, timeoutMs: Long = 1500, expectPrefix: String? = null): CommandResult
}

sealed class CommandResult {
    data class Ok(val response: String) : CommandResult()
    data class Error(val reason: String) : CommandResult()
    data object Timeout : CommandResult()
}
