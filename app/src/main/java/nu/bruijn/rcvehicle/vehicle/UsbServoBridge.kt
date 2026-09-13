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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Talks to the ESP32's Joystick Mode over a USB-OTG cable instead of its wifi AP -
 * the primary control path since 2026-09-14. A phone connected this way doesn't
 * need to juggle wifi-to-ESP32 alongside its own mobile-data uplink for Yggdrasil,
 * which is what ServoBridge's wifi approach required and where real-world testing
 * found Android's network routing to be unreliable. Bluetooth was considered for
 * the same reason but ruled out on flash budget - see the firmware's v1.22 commit
 * message for the numbers (Classic/BLEDevice ~800KB, NimBLE ~300KB, none of which
 * fit in this board's ~130KB of remaining flash).
 *
 * Sends the exact same "PosJ{ch}=<us>" lines as ServoBridge, over a raw serial byte
 * stream instead of a websocket - the firmware's usbJoystickLoop() (v1.22+) parses
 * them identically on the other end, sharing the same failsafe/channel-linking path.
 *
 * Uses mik3y/usb-serial-for-android, which implements the CP210x/CH34x/FTDI/PL2303
 * vendor protocols itself in Java rather than relying on a matching OEM kernel
 * driver - broad, consistent device support across phone brands is the whole
 * reason this library exists. res/xml/device_filter.xml lists the common chips
 * (this board uses Silicon Labs CP2102, confirmed via `lsusb`).
 */
class UsbServoBridge(
    private val context: Context,
    steerChannel: Int = 3,
    throttleChannel: Int = 4,
    centerUs: Int = 1500,
    minUs: Int = 1000,
    maxUs: Int = 2000,
    failsafeTimeoutMs: Long = 300,
) : ServoLink(steerChannel, throttleChannel, centerUs, minUs, maxUs, failsafeTimeoutMs) {

    private var port: UsbSerialPort? = null
    private var permissionReceiver: BroadcastReceiver? = null
    private var readJob: Job? = null
    private val lineBuffer = StringBuilder()

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

        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, Intent(action), PendingIntent.FLAG_MUTABLE
        )
        onStateChange?.invoke("requesting USB permission…")
        manager.requestPermission(driver.device, permissionIntent)
    }

    private fun openPort(manager: UsbManager, driver: UsbSerialDriver) {
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
            port = serialPort
            onStateChange?.invoke("USB serial connected")
            onConnected?.invoke(true)
            startWatchdog()
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
                    val n = p.read(buf, 500)
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

    private fun stopReading() {
        readJob?.cancel()
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

    /** Asks the board what firmware version it's running - "GETVERSION" -> "VERSION=<x.xx>". */
    fun checkFirmwareVersion(onResult: (version: String?) -> Unit) {
        val p = port
        if (p == null) {
            onResult(null)
            return
        }
        scope.launch {
            val previousHandler = onLineReceived
            val result = CompletableDeferred<String?>()
            onLineReceived = { line ->
                if (line.startsWith("VERSION=")) result.complete(line.removePrefix("VERSION=")) else previousHandler?.invoke(line)
            }
            startReading()
            try {
                synchronized(writeLock) { p.write("GETVERSION\n".toByteArray(), 200) }
                onResult(withTimeoutOrNull(3000) { result.await() })
            } catch (_: Exception) {
                onResult(null)
            } finally {
                onLineReceived = previousHandler
                stopReading()
            }
        }
    }

    /**
     * Flashes [data] (a full firmware.bin) onto the board over this same USB-serial
     * connection - see the firmware's usbFirmwareUpdate() (firmwareUpdate.h) for the
     * other end. Pauses the failsafe watchdog for the duration: it would otherwise
     * keep firing neutral "PosJ…" writes into the same byte stream the firmware
     * binary is being sent over, corrupting the image. Restarts it afterwards
     * regardless of outcome - if the board rebooted (success case), the underlying
     * USB-serial bridge chip stays enumerated across that (only the ESP32 MCU
     * itself resets, not the USB chip), so this same `port` keeps working once the
     * board finishes booting back up a couple seconds later.
     */
    fun flashFirmware(data: ByteArray, onProgress: (written: Int, total: Int) -> Unit, onResult: (success: Boolean, message: String) -> Unit) {
        val p = port
        if (p == null) {
            onResult(false, "not connected")
            return
        }
        stopWatchdog()
        scope.launch {
            val previousHandler = onLineReceived
            val result = CompletableDeferred<Pair<Boolean, String>>()
            onLineReceived = { line ->
                when {
                    line == "OTAOK" -> result.complete(true to "update installed, vehicle rebooting")
                    line.startsWith("OTAERROR:") -> result.complete(false to line.removePrefix("OTAERROR:"))
                    else -> previousHandler?.invoke(line)
                }
            }
            startReading()
            try {
                synchronized(writeLock) { p.write("OTAUPDATE=${data.size}\n".toByteArray(), 500) }
                var offset = 0
                val chunkSize = 2048
                while (offset < data.size) {
                    val end = minOf(offset + chunkSize, data.size)
                    synchronized(writeLock) { p.write(data.copyOfRange(offset, end), 5000) }
                    offset = end
                    onProgress(offset, data.size)
                }
                val outcome = withTimeoutOrNull(20_000) { result.await() }
                if (outcome != null) onResult(outcome.first, outcome.second) else onResult(false, "no response from board (timed out)")
            } catch (e: Exception) {
                onResult(false, "write failed: ${e.message}")
            } finally {
                onLineReceived = previousHandler
                stopReading()
                if (port != null) startWatchdog()
            }
        }
    }

    override fun stop() {
        super.stop()
        readJob?.cancel()
        readJob = null
        permissionReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        permissionReceiver = null
        val wasConnected = port != null
        port?.let { runCatching { it.close() } }
        port = null
        if (wasConnected) onConnected?.invoke(false)
    }
}
