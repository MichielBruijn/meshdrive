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
 * Hosts the wifi-to-ESP32 link (EspWifiNetworkBinder + ServoBridge) so it survives
 * screen-off/backgrounding. Signaling/RTC/camera land in a later pass; for this
 * stage (plan section "fase 2") it's driven by VehicleActivity's debug sliders
 * instead of a data channel.
 */
class VehicleForegroundService : Service() {

    inner class LocalBinder : Binder() {
        val service: VehicleForegroundService get() = this@VehicleForegroundService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val channelId = "vehicle_service"

    val servoBridge = ServoBridge()
    private lateinit var networkBinder: EspWifiNetworkBinder

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

    /**
     * Call once the user has entered/confirmed the ESP32's IP (bench-test debug UI).
     * Cancels any previous network observation first - without this, tapping
     * "Verbinden" more than once left earlier requestNetwork() registrations running
     * alongside the new one, each independently restarting ServoBridge's websocket
     * whenever its own onAvailable fired, which looked like random servo jitter.
     */
    fun connectToEsp(ip: String) {
        espIp = ip
        networkJob?.cancel()
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
        serviceScope.cancel()
        super.onDestroy()
    }
}
