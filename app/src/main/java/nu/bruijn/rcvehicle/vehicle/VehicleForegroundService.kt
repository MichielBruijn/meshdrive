package nu.bruijn.rcvehicle.vehicle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hosts both ways of reaching the ESP32 - USB-OTG serial (UsbServoBridge, primary
 * since 2026-09-14) and wifi (ServoBridge, kept as a fallback) - so whichever one
 * is active survives screen-off/backgrounding. `activeLink` is whichever the user
 * last successfully connected; VehicleActivity's control-input tick loop sends
 * through that regardless of which transport it is.
 */
class VehicleForegroundService : Service() {

    inner class LocalBinder : Binder() {
        val service: VehicleForegroundService get() = this@VehicleForegroundService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val channelId = "vehicle_service"

    private val servoBridge = ServoBridge()

    // Public (not just `activeLink`): firmware updates need this specific transport
    // regardless of which link is currently relaying joystick commands - USB is a
    // physical cable, not a "connect via" choice like wifi is.
    val usbServoBridge = UsbServoBridge(this)
    private lateinit var networkBinder: EspWifiNetworkBinder

    var activeLink: ServoLink? = null
        private set

    var onStatus: ((String) -> Unit)? = null
    private var espIp: String = "192.168.4.1"
    private var networkJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Vehicle link", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        networkBinder = EspWifiNetworkBinder(applicationContext)
        servoBridge.onStateChange = { status -> onStatus?.invoke(status) }
        usbServoBridge.onStateChange = { status -> onStatus?.invoke(status) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("RC Vehicle")
            .setContentText("Wachten op besturing…")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(1, notification)
        return START_STICKY
    }

    /** USB-OTG serial - the primary path. See UsbServoBridge's doc for why. */
    fun connectUsb() {
        activeLink?.stop()
        activeLink = usbServoBridge
        usbServoBridge.start()
    }

    /**
     * Wifi fallback: call once the user has entered/confirmed the ESP32's IP.
     * Cancels any previous network observation first - without this, tapping
     * "Connect" more than once left earlier requestNetwork() registrations running
     * alongside the new one, each independently restarting ServoBridge's websocket
     * whenever its own onAvailable fired, which looked like random servo jitter.
     */
    fun connectToEsp(ip: String) {
        espIp = ip
        networkJob?.cancel()
        activeLink?.stop()
        activeLink = servoBridge
        onStatus?.invoke("requesting wifi network…")
        networkJob = serviceScope.launch {
            networkBinder.observe().collect { state ->
                when (state) {
                    is EspWifiNetworkBinder.State.Requesting ->
                        onStatus?.invoke("requesting wifi network…")
                    is EspWifiNetworkBinder.State.Available -> {
                        onStatus?.invoke("wifi network acquired")
                        servoBridge.start(state.network, espIp)
                    }
                    is EspWifiNetworkBinder.State.Lost -> {
                        onStatus?.invoke("wifi network lost")
                        servoBridge.stop()
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        servoBridge.stop()
        usbServoBridge.stop()
        serviceScope.cancel()
        super.onDestroy()
    }
}
