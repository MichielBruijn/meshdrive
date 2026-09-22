package nu.bruijn.rcvehicle.vehicle

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * BLE alternative to UsbServoBridge - talks to the ESP32-C3's Nordic UART Service
 * ("MeshDrive"), same "PosJ{ch}=<us>"/"SteerLimitOn=" lines. Never calls
 * createBond() - the firmware's NUS characteristics are unauthenticated by design.
 * Untested on real hardware yet, unlike the rest of this file's siblings.
 */
class BleServoBridge(
    private val context: Context,
    centerUs: Int = 1500,
    minUs: Int = 1000,
    maxUs: Int = 2000,
    failsafeTimeoutMs: Long = 300,
) : ServoLink(centerUs, minUs, maxUs, failsafeTimeoutMs), ServoLinkCommands {

    companion object {
        private val SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val RX_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        private val TX_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }

    // Serializes GATT ops - BluetoothGatt allows only one outstanding at a time.
    private val gattLock = Mutex()

    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var scanCallback: ScanCallback? = null
    private var mtuPayload = 20
    private var pendingWrite: CompletableDeferred<Boolean>? = null
    private var pendingDescriptorWrite: CompletableDeferred<Boolean>? = null
    private var pendingMtuChange: CompletableDeferred<Boolean>? = null
    private val lineBuffer = StringBuilder()

    var onLineReceived: ((String) -> Unit)? = null
    var onConnected: ((Boolean) -> Unit)? = null

    private fun log(msg: String) {
        Log.d("BleServoBridge", msg)
        onStateChange?.invoke(msg)
    }

    private fun hasBlePermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)
    }

    fun start() {
        stop()
        if (!hasBlePermissions()) {
            log("BLE permission not granted")
            onConnected?.invoke(false)
            return
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            log("Bluetooth is off")
            onConnected?.invoke(false)
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            log("BLE scan unavailable")
            onConnected?.invoke(false)
            return
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                try {
                    scanner.stopScan(this)
                } catch (_: SecurityException) {
                }
                scanCallback = null
                log("found board, connecting…")
                connectTo(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                scanCallback = null
                log("BLE scan failed: $errorCode")
                onConnected?.invoke(false)
            }
        }
        scanCallback = callback
        log("scanning for board…")
        try {
            scanner.startScan(listOf(filter), settings, callback)
        } catch (e: SecurityException) {
            log("BLE permission denied")
            onConnected?.invoke(false)
        }
    }

    private fun connectTo(device: BluetoothDevice) {
        try {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            log("BLE connect permission denied")
            onConnected?.invoke(false)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("connection state change: status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("BLE connected, discovering services…")
                try {
                    // Default connection priority negotiates a much longer interval than
                    // real-time servo control can tolerate - this is what "choppy" looks like.
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    g.discoverServices()
                } catch (_: SecurityException) {
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasConnected = rxCharacteristic != null
                rxCharacteristic = null
                try {
                    g.close()
                } catch (_: Exception) {
                }
                if (gatt === g) gatt = null
                if (wasConnected) {
                    log("BLE disconnected")
                    onConnected?.invoke(false)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("service discovery failed: $status")
                onConnected?.invoke(false)
                try {
                    g.disconnect()
                } catch (_: SecurityException) {
                }
                return
            }
            val service = g.getService(SERVICE_UUID)
            val rx = service?.getCharacteristic(RX_UUID)
            val tx = service?.getCharacteristic(TX_UUID)
            if (rx == null || tx == null) {
                log("board doesn't expose the expected UART service")
                onConnected?.invoke(false)
                try {
                    g.disconnect()
                } catch (_: SecurityException) {
                }
                return
            }
            rxCharacteristic = rx
            // One coroutine, sequential - setupNotifications() awaits its own descriptor-write/
            // MTU completion callbacks before returning (see its own doc), so neither
            // resolveBoardConfig()'s first command nor anything a caller fires off
            // onConnected(true) (VehicleActivity's automatic GETVERSION update check) is racing
            // them for the GATT's single in-flight-operation slot - "connected" isn't reported
            // until commands can actually get a reply. Confirmed on real hardware as a genuine,
            // total-failure bug without this: resolveBoardConfig() used to fire the moment
            // "BLE ready" logged (a separately-launched, not-awaited coroutine did the
            // notification/MTU setup), and every one of its GATT writes was synchronously
            // rejected (writeCharacteristic() returning false, not even queued) because the
            // CCCD descriptor write/MTU negotiation were still in flight - all 3 retry attempts
            // burned through in under a millisecond with no backoff between them, so
            // resolveBoardConfig() gave up and left this permanently not-ready, well before the
            // GATT bus actually freed up ~30-50ms later.
            scope.launch {
                setupNotifications(g, tx)
                // Dropped (or stop()ped) while setup was still awaiting its callbacks - that
                // path already reported disconnected; announcing "ready" now would contradict it.
                if (gatt !== g || rxCharacteristic == null) return@launch
                log("BLE ready")
                onConnected?.invoke(true)
                startWatchdog()
                resolveBoardConfig()
            }
        }

        // Awaits its own descriptor-write/MTU-negotiation completion callbacks (not just
        // queuing them and moving on) - see onServicesDiscovered's doc for why. Bounded
        // timeouts, not indefinite: a callback that never fires (unlikely, but real hardware
        // is real hardware) shouldn't wedge the connection forever.
        private suspend fun setupNotifications(g: BluetoothGatt, tx: BluetoothGattCharacteristic) {
            gattLock.withLock {
                try {
                    @Suppress("DEPRECATION")
                    g.setCharacteristicNotification(tx, true)
                    val cccd = tx.getDescriptor(CCCD_UUID)
                    if (cccd != null) {
                        val deferred = CompletableDeferred<Boolean>()
                        pendingDescriptorWrite = deferred
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        if (g.writeDescriptor(cccd)) {
                            withTimeoutOrNull(2000) { deferred.await() }
                        }
                        pendingDescriptorWrite = null
                    }
                    val mtuDeferred = CompletableDeferred<Boolean>()
                    pendingMtuChange = mtuDeferred
                    if (g.requestMtu(517)) {
                        withTimeoutOrNull(2000) { mtuDeferred.await() }
                    }
                    pendingMtuChange = null
                } catch (_: SecurityException) {
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU changed: status=$status mtu=$mtu")
            if (status == BluetoothGatt.GATT_SUCCESS) mtuPayload = mtu - 3
            pendingMtuChange?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            pendingDescriptorWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != TX_UUID) return
            val value = characteristic.value ?: return
            Log.d("BleServoBridge", "notify: ${value.size} bytes, hasHandler=${onLineReceived != null}")
            for (b in value) {
                when (val c = b.toInt().toChar()) {
                    '\n' -> {
                        val line = lineBuffer.toString().trim()
                        lineBuffer.clear()
                        if (line.isNotEmpty()) {
                            Log.d("BleServoBridge", "line: $line")
                            onLineReceived?.invoke(line)
                        }
                    }
                    '\r' -> {}
                    else -> lineBuffer.append(c)
                }
            }
        }
    }

    override fun send(channel: Int, us: Int) {
        writeLine("PosJ$channel=$us", BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
    }

    override fun setSteerLimitEnabled(enabled: Boolean) {
        writeLine("SteerLimitOn=${if (enabled) 1 else 0}", BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
    }

    private fun writeLine(line: String, writeType: Int) {
        val g = gatt ?: return
        val rx = rxCharacteristic ?: return
        scope.launch {
            gattLock.withLock { writeChunked(g, rx, "$line\n".toByteArray(), writeType) }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun writeChunked(g: BluetoothGatt, rx: BluetoothGattCharacteristic, data: ByteArray, writeType: Int): Boolean {
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + mtuPayload, data.size)
            val deferred = CompletableDeferred<Boolean>()
            pendingWrite = deferred
            try {
                rx.writeType = writeType
                rx.value = data.copyOfRange(offset, end)
                if (!g.writeCharacteristic(rx)) return false
            } catch (_: SecurityException) {
                return false
            }
            if (withTimeoutOrNull(2000) { deferred.await() } != true) return false
            pendingWrite = null
            offset = end
        }
        return true
    }

    /**
     * Queries the board's real Joystick X/Y channels and calibration on every fresh connect -
     * same reasoning as UsbServoBridge's checkJoystickChannels()/checkCalibration(). Per direct
     * request, this app must never guess/hardcode which physical channel steer or throttle is
     * on - readyToSend only ever flips true once the board itself has confirmed them; if it
     * can't (old firmware, no reply), this bridge just stays not-ready rather than falling back
     * to anything. Confirmed on real hardware as a genuine, safety-relevant bug without this at
     * all: channel drift on the board's own EEPROM (a settings change, a fresh-flash/erase)
     * silently left this class driving the wrong physical channel with no way to notice or
     * self-correct - throttle stopped responding after the board's channels drifted to 0/3, and
     * BLE had no way to detect or recover from it, unlike USB. Wasn't reachable before the BLE
     * reply newline fix (controlReply() in src.ino) either - every one of these commands timed
     * out silently on real hardware until then. Up to 3 attempts each, same reasoning as
     * UsbServoBridge's own retry loop: a cold BLE connect's very first command can race
     * notification setup (the CCCD write/MTU negotiation above are fire-and-forget, not
     * awaited).
     */
    private suspend fun resolveBoardConfig() {
        readyToSend = false
        var channels: Pair<Int, Int>? = null
        for (attempt in 1..3) {
            val result = sendCommand("GETJOYCHANNELS", 1500, "JOYCHANNELS=")
            if (result is CommandResult.Ok) {
                val parts = result.response.removePrefix("JOYCHANNELS=").split(",")
                val steer = parts.getOrNull(0)?.toIntOrNull()
                val throttle = parts.getOrNull(1)?.toIntOrNull()
                if (steer != null && throttle != null) channels = steer to throttle
            }
            if (channels != null) break
        }
        if (channels == null) {
            log("could not read board's servo channels - control disabled")
            return
        }
        val (steer, throttle) = channels
        setChannels(steer, throttle)
        log("using board's channels: steer=CH${steer + 1} throttle=CH${throttle + 1}")

        var calibration: List<Int>? = null
        for (attempt in 1..3) {
            val result = sendCommand("GETCALIBRATION", 1500, "CALIBRATION=")
            if (result is CommandResult.Ok) {
                val parts = result.response.removePrefix("CALIBRATION=").split(",").mapNotNull { it.toIntOrNull() }
                if (parts.size == 6) calibration = parts
            }
            if (calibration != null) break
        }
        // null here just means older firmware / no reply - the generic 1000/1500/2000
        // default stays (calibration range, unlike the channel itself, isn't the "wrong
        // physical output" hazard the channel-guessing concern was about).
        calibration?.let { c -> setCalibration(c[0], c[1], c[2], c[3], c[4], c[5]) }

        readyToSend = true
    }

    // Same reasoning as UsbServoBridge's own sendCommandMutex: this shares one onLineReceived
    // slot across all callers, so concurrent calls can steal each other's reply or restore the
    // handler out of order - a dedicated mutex around the whole call (not just gattLock's own
    // write-chunking) is the fix. A different Mutex than gattLock, since Kotlin's Mutex isn't
    // reentrant and writeChunked() below still needs its own lock.
    private val sendCommandMutex = Mutex()

    override suspend fun sendCommand(command: String, timeoutMs: Long, expectPrefix: String?): CommandResult = sendCommandMutex.withLock {
        Log.d("BleServoBridge", "sendCommand: $command (gatt=${gatt != null}, rx=${rxCharacteristic != null})")
        val g = gatt ?: return@withLock CommandResult.Error("not connected")
        val rx = rxCharacteristic ?: return@withLock CommandResult.Error("not connected")
        val previousHandler = onLineReceived
        val result = CompletableDeferred<CommandResult>()
        onLineReceived = { line ->
            when {
                line.startsWith("ERROR:") -> result.complete(CommandResult.Error(line.removePrefix("ERROR:")))
                expectPrefix == null || line.startsWith(expectPrefix) -> result.complete(CommandResult.Ok(line))
            }
        }
        try {
            val sent = gattLock.withLock { writeChunked(g, rx, "$command\n".toByteArray(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) }
            Log.d("BleServoBridge", "sendCommand: $command write sent=$sent")
            if (!sent) return@withLock CommandResult.Error("write failed")
            val outcome = withTimeoutOrNull(timeoutMs) { result.await() } ?: CommandResult.Timeout
            Log.d("BleServoBridge", "sendCommand: $command outcome=$outcome")
            outcome
        } finally {
            onLineReceived = previousHandler
        }
    }

    override fun stop() {
        super.stop()
        scanCallback?.let { cb ->
            try {
                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                adapter?.bluetoothLeScanner?.stopScan(cb)
            } catch (_: SecurityException) {
            }
        }
        scanCallback = null
        val wasConnected = rxCharacteristic != null
        rxCharacteristic = null
        gatt?.let { g ->
            try {
                g.disconnect()
                g.close()
            } catch (_: SecurityException) {
            }
        }
        gatt = null
        lineBuffer.clear()
        if (wasConnected) {
            log("BLE disconnected")
            onConnected?.invoke(false)
        }
    }
}
