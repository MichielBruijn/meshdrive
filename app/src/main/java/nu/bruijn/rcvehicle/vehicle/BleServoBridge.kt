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
    steerChannel: Int = 3,
    throttleChannel: Int = 4,
    centerUs: Int = 1500,
    minUs: Int = 1000,
    maxUs: Int = 2000,
    failsafeTimeoutMs: Long = 300,
) : ServoLink(steerChannel, throttleChannel, centerUs, minUs, maxUs, failsafeTimeoutMs), ServoLinkCommands {

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
            scope.launch {
                gattLock.withLock {
                    try {
                        @Suppress("DEPRECATION")
                        g.setCharacteristicNotification(tx, true)
                        val cccd = tx.getDescriptor(CCCD_UUID)
                        if (cccd != null) {
                            @Suppress("DEPRECATION")
                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            g.writeDescriptor(cccd)
                        }
                        g.requestMtu(517)
                    } catch (_: SecurityException) {
                    }
                }
            }
            log("BLE ready")
            onConnected?.invoke(true)
            startWatchdog()
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU changed: status=$status mtu=$mtu")
            if (status == BluetoothGatt.GATT_SUCCESS) mtuPayload = mtu - 3
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != TX_UUID) return
            val value = characteristic.value ?: return
            for (b in value) {
                when (val c = b.toInt().toChar()) {
                    '\n' -> {
                        val line = lineBuffer.toString().trim()
                        lineBuffer.clear()
                        if (line.isNotEmpty()) onLineReceived?.invoke(line)
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

    // Same reasoning as UsbServoBridge's own sendCommandMutex: this shares one onLineReceived
    // slot across all callers, so concurrent calls can steal each other's reply or restore the
    // handler out of order - a dedicated mutex around the whole call (not just gattLock's own
    // write-chunking) is the fix. A different Mutex than gattLock, since Kotlin's Mutex isn't
    // reentrant and writeChunked() below still needs its own lock.
    private val sendCommandMutex = Mutex()

    override suspend fun sendCommand(command: String, timeoutMs: Long): CommandResult = sendCommandMutex.withLock {
        val g = gatt ?: return@withLock CommandResult.Error("not connected")
        val rx = rxCharacteristic ?: return@withLock CommandResult.Error("not connected")
        val previousHandler = onLineReceived
        val result = CompletableDeferred<CommandResult>()
        onLineReceived = { line ->
            when {
                line.startsWith("ERROR:") -> result.complete(CommandResult.Error(line.removePrefix("ERROR:")))
                else -> result.complete(CommandResult.Ok(line))
            }
        }
        try {
            val sent = gattLock.withLock { writeChunked(g, rx, "$command\n".toByteArray(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) }
            if (!sent) return@withLock CommandResult.Error("write failed")
            withTimeoutOrNull(timeoutMs) { result.await() } ?: CommandResult.Timeout
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
