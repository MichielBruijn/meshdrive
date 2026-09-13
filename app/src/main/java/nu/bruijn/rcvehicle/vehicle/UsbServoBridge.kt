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
import kotlinx.coroutines.launch

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

    fun start() {
        stop() // clean up any previous connection first

        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()
        if (driver == null) {
            onStateChange?.invoke("no USB-serial device found")
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
            return
        }
        val serialPort = driver.ports[0]
        try {
            serialPort.open(connection)
            serialPort.setParameters(115_200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port = serialPort
            onStateChange?.invoke("USB serial connected")
            startWatchdog()
        } catch (e: Exception) {
            onStateChange?.invoke("USB open failed: ${e.message}")
        }
    }

    override fun send(channel: Int, us: Int) {
        val p = port ?: return
        scope.launch {
            try {
                p.write("PosJ$channel=$us\n".toByteArray(), 200)
            } catch (_: Exception) {
                // best-effort, same reasoning as the wifi path - a dropped write
                // is fine, the failsafe/next tick handles it
            }
        }
    }

    override fun stop() {
        super.stop()
        permissionReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        permissionReceiver = null
        port?.let { runCatching { it.close() } }
        port = null
    }
}
