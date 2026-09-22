package nu.bruijn.rcvehicle.vehicle

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Talks to the ESP32's Joystick Mode over a USB-OTG cable instead of its wifi AP -
 * the primary control path since 2026-09-14. A phone connected this way doesn't
 * need to juggle wifi-to-ESP32 alongside its own mobile-data uplink for Yggdrasil,
 * which is what ServoBridge's wifi approach required and where real-world testing
 * found Android's network routing to be unreliable.
 *
 * Bluetooth was ruled out on flash budget when this class was first written
 * (Classic/BLEDevice ~800KB, NimBLE ~300KB, neither fit the classic ESP32 board's
 * ~130KB of remaining flash at firmware v1.22) - that board has since been replaced
 * by the ESP32-C3 SuperMini in the vehicle, and BLE fit there once its partition
 * table was switched to min_spiffs.csv (this project never used a filesystem). The
 * C3 firmware (private repo esp32-c3-servo-controller, branch main) now advertises
 * a Nordic UART Service as "MeshDrive" alongside this USB-serial path - not yet
 * consumed from this app, see BleServoBridge (once it exists) for that side.
 *
 * Sends the exact same "PosJ{ch}=<us>" lines as ServoBridge, over a raw serial byte
 * stream instead of a websocket - the firmware's usbJoystickLoop() (v1.22+) parses
 * them identically on the other end, sharing the same failsafe/channel-linking path.
 *
 * Uses mik3y/usb-serial-for-android, which implements the CP210x/CH34x/FTDI/PL2303
 * vendor protocols itself in Java rather than relying on a matching OEM kernel
 * driver - broad, consistent device support across phone brands is the whole
 * reason this library exists. res/xml/device_filter.xml lists the common chips.
 * NOTE: this was written for the classic ESP32 board (Silicon Labs CP2102 bridge
 * chip, confirmed via `lsusb` at the time) - the C3 SuperMini now in the vehicle
 * enumerates completely differently (native USB-CDC/JTAG, vendor:product 303a:1001,
 * "Espressif USB JTAG/serial debug unit", confirmed via `lsusb` 2026-09-21), which
 * is NOT in device_filter.xml. Unverified whether findAllDrivers() below (which
 * matches against the library's own internal ID tables, independent of
 * device_filter.xml) still recognizes it - device_filter.xml only controls
 * Android's auto-launch-on-plug prompt, so this may still work for an
 * already-foregrounded app while silently losing auto-launch. Check both before
 * relying on this path with the current board.
 */
class UsbServoBridge(
    private val context: Context,
    centerUs: Int = 1500,
    minUs: Int = 1000,
    maxUs: Int = 2000,
    failsafeTimeoutMs: Long = 300,
) : ServoLink(centerUs, minUs, maxUs, failsafeTimeoutMs), ServoLinkCommands {

    private var port: UsbSerialPort? = null
    private var permissionReceiver: BroadcastReceiver? = null
    private var readJob: Job? = null
    private val lineBuffer = StringBuilder()

    // Bumped by every stop() (so also by start()'s own leading stop()) - lets
    // openPort()'s checkJoystickChannels/checkCalibration chain tell whether it's
    // still the current connection attempt before touching setChannels()/
    // setCalibration()/readyToSend/onLineReceived. Needed since VehicleForegroundService
    // now has two paths that can both call connectUsb() close together on a cold
    // start (its own USB_DEVICE_ATTACHED receiver, and VehicleActivity's
    // onServiceConnected) - confirmed on real hardware as a real bug: the older of
    // two overlapping start() calls closes the port the newer one just opened
    // (or vice versa), and each one's query used to blindly install itself as
    // onLineReceived and restore whatever it last saw as "previous" in finally -
    // two interleaved GETJOYCHANNELS/GETCALIBRATION round-trips on the same port
    // could each grab the other's reply, resolving one channel correctly and
    // leaving the other on its placeholder default (matching the "only one servo
    // moves" symptom exactly).
    private val connectionGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Fires for each newline-terminated line the board sends back - normal
     * operation never uses this (the servo link is send-only), but
     * checkFirmwareVersion()/flashFirmware() below temporarily install their own
     * handler here to catch "VERSION=…"/"OTAOK"/"OTAERROR:…", restoring whatever
     * was here before once they get their answer (or time out).
     */
    var onLineReceived: ((String) -> Unit)? = null

    /**
     * Fires true/false specifically for "is the port open" - separate from the
     * free-text onStateChange above, which is a running log line and not meant to
     * be pattern-matched by callers. Lets VehicleActivity give the USB button a
     * clear connected/not-connected look without string-matching status messages.
     */
    var onConnected: ((Boolean) -> Unit)? = null

    /** Fires once the board's real Joystick X/Y channels are actually confirmed
     * (GETJOYCHANNELS succeeded) - lets the caller persist them and hand them to
     * the wifi fallback (ServoBridge), which has no equivalent query of its own
     * over HTTP/websocket (the firmware only answers GETJOYCHANNELS/GETCALIBRATION
     * on the serial line - confirmed while investigating, no JSON/HTTP equivalent
     * exists). Per direct request: a board's real channel mapping shouldn't only
     * ever be known while a USB cable happens to be plugged in. */
    var onChannelsResolved: ((steer: Int, throttle: Int) -> Unit)? = null

    /** Same reasoning as [onChannelsResolved], for GETCALIBRATION. */
    var onCalibrationResolved: ((Calibration) -> Unit)? = null

    fun start() {
        stop() // clean up any previous connection first

        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()
        if (driver == null) {
            onStateChange?.invoke("no USB-serial device found")
            onConnected?.invoke(false)
            return
        }

        if (manager.hasPermission(driver.device)) {
            openPort(manager, driver)
        } else {
            requestPermission(manager, driver)
        }
    }

    private fun requestPermission(manager: UsbManager, driver: UsbSerialDriver) {
        val action = "${context.packageName}.USB_PERMISSION"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != action) return
                context.unregisterReceiver(this)
                permissionReceiver = null
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    openPort(manager, driver)
                } else {
                    onStateChange?.invoke("USB permission denied")
                    onConnected?.invoke(false)
                }
            }
        }
        permissionReceiver = receiver
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)

        // .setPackage() makes this Intent explicit rather than a bare action
        // string - confirmed on real hardware as a crash without it: Android 14+
        // (targetSdk 34+) throws IllegalArgumentException for a FLAG_MUTABLE
        // PendingIntent wrapping an implicit Intent, and it has to stay mutable
        // since UsbManager.requestPermission() fills in EXTRA_PERMISSION_GRANTED/
        // EXTRA_DEVICE on it before firing - FLAG_IMMUTABLE would silently drop
        // those. Doesn't change matching: the receiver above is already scoped
        // to this app only (RECEIVER_NOT_EXPORTED).
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_MUTABLE
        )
        onStateChange?.invoke("requesting USB permission…")
        manager.requestPermission(driver.device, permissionIntent)
    }

    private fun openPort(manager: UsbManager, driver: UsbSerialDriver) {
        // Captured AFTER start()'s leading stop() already bumped this - see
        // connectionGeneration's doc. Any of the three checks below finding it's
        // since moved on means a newer start()/stop() has taken over; bail out
        // without touching setChannels()/setCalibration()/readyToSend/
        // onLineReceived; that newer attempt owns all of those now.
        val myGeneration = connectionGeneration.get()
        val connection = manager.openDevice(driver.device)
        if (connection == null) {
            onStateChange?.invoke("failed to open USB device")
            onConnected?.invoke(false)
            return
        }
        val serialPort = driver.ports[0]
        try {
            serialPort.open(connection)
            serialPort.setParameters(115_200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            if (connectionGeneration.get() != myGeneration) {
                runCatching { serialPort.close() }
                return
            }
            port = serialPort
            onStateChange?.invoke("USB serial connected")
            onConnected?.invoke(true)
            startWatchdog()
            // Held false until the board's real channels are confirmed below - see
            // readyToSend's doc in ServoLink: per direct request, this must never fire on a
            // guessed/hardcoded channel, only ever the board's own actual configured ones.
            readyToSend = false
            checkJoystickChannels { steer, throttle ->
                if (connectionGeneration.get() != myGeneration) return@checkJoystickChannels
                if (steer == null || throttle == null) {
                    // Older firmware that doesn't know GETJOYCHANNELS, or no reply in time -
                    // stays not-ready rather than guessing a channel. No retry loop here
                    // beyond checkJoystickChannels()'s own 3 attempts - a stop()/start() cycle
                    // (unplug/replug, or the app's own reconnect) tries again from scratch.
                    onStateChange?.invoke("could not read board's servo channels - control disabled")
                    return@checkJoystickChannels
                }
                setChannels(steer, throttle)
                onStateChange?.invoke("using board's channels: steer=CH${steer + 1} throttle=CH${throttle + 1}")
                onChannelsResolved?.invoke(steer, throttle)
                checkCalibration { calibration ->
                    if (connectionGeneration.get() != myGeneration) return@checkCalibration
                    if (calibration != null) {
                        val (steerMin, steerCenter, steerMax, throttleMin, throttleCenter, throttleMax) = calibration
                        setCalibration(steerMin, steerCenter, steerMax, throttleMin, throttleCenter, throttleMax)
                        onStateChange?.invoke("using board's calibration: steer=$steerMin/$steerCenter/$steerMax throttle=$throttleMin/$throttleCenter/$throttleMax")
                        onCalibrationResolved?.invoke(calibration)
                    }
                    // null means an older firmware that doesn't know GETCALIBRATION, or no
                    // reply in time - the generic 1000/1500/2000 default stays (calibration
                    // range, unlike the channel itself, isn't the "wrong physical output"
                    // hazard the user's channel-guessing concern was about).
                    readyToSend = true
                }
            }
        } catch (e: Exception) {
            onStateChange?.invoke("USB open failed: ${e.message}")
            onConnected?.invoke(false)
        }
    }

    /**
     * Background read loop - only ever started for the brief window
     * checkFirmwareVersion()/flashFirmware() below need a reply in, NOT for the
     * whole time the port is open. Android's UsbDeviceConnection explicitly does
     * not guarantee bulkTransfer() is safe to call concurrently from multiple
     * threads, even to different endpoints (IN here, OUT for the normal
     * "PosJ…" writes) - running this continuously during ordinary driving
     * intermittently corrupted/dropped those writes (confirmed on real hardware:
     * servos stopped responding to the app, though the board's own physical menu
     * still worked fine, i.e. the board itself was never the problem).
     */
    private fun startReading() {
        readJob?.cancel()
        readJob = scope.launch {
            val buf = ByteArray(1024)
            while (isActive) {
                val p = port ?: break
                try {
                    val n = p.read(buf, 100)
                    for (i in 0 until n) {
                        when (val c = buf[i].toInt().toChar()) {
                            '\n' -> {
                                val line = lineBuffer.toString().trim()
                                lineBuffer.clear()
                                if (line.isNotEmpty()) onLineReceived?.invoke(line)
                            }
                            '\r' -> {}
                            else -> lineBuffer.append(c)
                        }
                    }
                } catch (_: Exception) {
                    if (!isActive) break
                }
            }
        }
    }

    // suspend + cancelAndJoin(), not a plain cancel(): Job.cancel() only marks the
    // coroutine cancelled, it does not wait for an already in-flight, blocking
    // p.read() call to actually return - that call keeps running (up to its own
    // timeout) even after "stop". Callers that immediately start writing right
    // after stopReading() could still race that lingering read against their own
    // writes on the same UsbDeviceConnection - confirmed on real hardware as
    // firmware bytes corrupting partway through a flash ("wrong magic byte" a few
    // KB in, not just at the very start) even after the read loop was nominally
    // "stopped" first.
    private suspend fun stopReading() {
        readJob?.cancelAndJoin()
        readJob = null
        lineBuffer.clear()
    }

    // Serializes writes to the port: sendControl() (called from the UI tick loop's
    // thread) and the failsafe watchdog (its own coroutine) can both call send()
    // at close to the same time. Unlike ServoBridge's webSocket.send() - already
    // thread-safe/queued internally by OkHttp - UsbSerialPort.write() is a raw
    // blocking call with no such guarantee, so two overlapping writes from
    // different threads could interleave their bytes on the wire. That showed up
    // as visibly jittery servo movement (corrupted/rejected PosJ lines) before
    // this lock was added.
    private val writeLock = Any()

    override fun send(channel: Int, us: Int) {
        writeLine("PosJ$channel=$us")
    }

    override fun setSteerLimitEnabled(enabled: Boolean) {
        writeLine("SteerLimitOn=${if (enabled) 1 else 0}")
    }

    private fun writeLine(line: String) {
        val p = port ?: return
        scope.launch {
            try {
                synchronized(writeLock) {
                    p.write("$line\n".toByteArray(), 200)
                }
            } catch (_: Exception) {
                // best-effort, same reasoning as the wifi path - a dropped write
                // is fine, the failsafe/next tick handles it
            }
        }
    }

    /** Asks the board which channels its own Joystick X/Y are currently configured
     * for - "GETJOYCHANNELS" -> "JOYCHANNELS=<steer>,<throttle>" - see
     * ServoLink.setChannels()'s doc for why. Called automatically once per
     * connect (openPort() above); null means an older firmware that doesn't
     * recognize the command, or no reply within the timeout - openPort() then
     * leaves control disabled rather than guessing a channel.
     *
     * Goes through sendCommand() (not its own read loop anymore) so it's
     * serialized with every other command by sendCommandMutex - e.g. the
     * automatic GETVERSION update check VehicleActivity fires on every connect,
     * which used to be able to race this for the port's read stream. Up to 3
     * attempts, not 1 - confirmed on real hardware as a real gap: a single shot
     * right after openPort() was unreliable specifically on a COLD connect (app
     * launched with the board already plugged in, USB bring-up still settling),
     * while a WARM reconnect consistently got a reply first try. */
    fun checkJoystickChannels(onResult: (steer: Int?, throttle: Int?) -> Unit) {
        scope.launch {
            var channels: Pair<Int, Int>? = null
            for (attempt in 1..3) {
                val result = sendCommand("GETJOYCHANNELS", 1500, "JOYCHANNELS=")
                if (result is CommandResult.Ok) {
                    val parts = result.response.removePrefix("JOYCHANNELS=").split(",")
                    val steer = parts.getOrNull(0)?.toIntOrNull()
                    val throttle = parts.getOrNull(1)?.toIntOrNull()
                    if (parts.size == 2 && steer != null && throttle != null) channels = steer to throttle
                }
                if (channels != null) break
            }
            onResult(channels?.first, channels?.second)
        }
    }

    /** Steer/throttle µs calibration as reported by GETCALIBRATION - see checkCalibration(). */
    data class Calibration(
        val steerMin: Int,
        val steerCenter: Int,
        val steerMax: Int,
        val throttleMin: Int,
        val throttleCenter: Int,
        val throttleMax: Int,
    )

    /** Asks the board for its own calibrated Min/Center/Max µs per steer/throttle
     * channel - "GETCALIBRATION" -> "CALIBRATION=<steerMin>,<steerCenter>,<steerMax>,
     * <throttleMin>,<throttleCenter>,<throttleMax>" - see ServoLink.setCalibration()'s
     * doc for why. Called automatically right after checkJoystickChannels() in
     * openPort(); null just means an older firmware that doesn't recognize the
     * command, or no reply within the timeout - callers should leave the existing
     * (generic RC default) calibration in place either way. */
    fun checkCalibration(onResult: (Calibration?) -> Unit) {
        scope.launch {
            // Same sendCommand()/retry reasoning as checkJoystickChannels() above.
            var calibration: Calibration? = null
            for (attempt in 1..3) {
                val result = sendCommand("GETCALIBRATION", 1500, "CALIBRATION=")
                if (result is CommandResult.Ok) {
                    val parts = result.response.removePrefix("CALIBRATION=").split(",").mapNotNull { it.toIntOrNull() }
                    if (parts.size == 6) calibration = Calibration(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5])
                }
                if (calibration != null) break
            }
            onResult(calibration)
        }
    }

    // sendCommand() is NOT safe for concurrent callers on its own - they'd all share this one
    // onLineReceived slot and the single readJob started/stopped by startReading()/stopReading(),
    // so two overlapping calls can steal each other's reply or restore onLineReceived out of
    // order. Confirmed on real hardware as a genuine bug: the now-removed native servo-settings
    // screen fired several of these back to back (its own initial channel/joystick-links/wifi
    // load, or just two button taps close together), and whichever call lost the race got
    // someone else's reply, a timeout, or clobbered the "restore previous handler" chain -
    // directly matching the reported symptom (channel buttons working sometimes, joystick rows
    // going blank, previously populated data disappearing on a later reload) and, worse, real
    // EEPROM writes landing on the wrong channel. A mutex here, not per-caller
    // coordination, is the fix - every call site (present or future) gets this for free.
    private val sendCommandMutex = Mutex()

    override suspend fun sendCommand(command: String, timeoutMs: Long, expectPrefix: String?): CommandResult = sendCommandMutex.withLock {
        val p = port ?: return@withLock CommandResult.Error("not connected")
        val previousHandler = onLineReceived
        val result = CompletableDeferred<CommandResult>()
        onLineReceived = { line ->
            when {
                line.startsWith("ERROR:") -> result.complete(CommandResult.Error(line.removePrefix("ERROR:")))
                expectPrefix == null || line.startsWith(expectPrefix) -> result.complete(CommandResult.Ok(line))
            }
        }
        startReading()
        try {
            synchronized(writeLock) { p.write("$command\n".toByteArray(), 200) }
            withTimeoutOrNull(timeoutMs) { result.await() } ?: CommandResult.Timeout
        } catch (e: Exception) {
            CommandResult.Error(e.message ?: "write failed")
        } finally {
            onLineReceived = previousHandler
            stopReading()
        }
    }

    override fun stop() {
        connectionGeneration.incrementAndGet()
        super.stop()
        readJob?.cancel()
        readJob = null
        permissionReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        permissionReceiver = null
        val wasConnected = port != null
        port?.let { runCatching { it.close() } }
        port = null
        if (wasConnected) {
            onStateChange?.invoke("USB disconnected")
            onConnected?.invoke(false)
        }
    }
}
