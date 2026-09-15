package nu.bruijn.rcvehicle.vehicle

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
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

    // Screen timeout locking the vehicle phone must not stop it sending - a real,
    // reported requirement, not a hypothetical one. A correctly-typed foreground
    // service (see the manifest: camera+microphone alongside connectedDevice, plus
    // the matching FOREGROUND_SERVICE_CAMERA/MICROPHONE permissions - both required
    // since Android 14 to use those APIs from a backgrounded process at all) covers
    // the "is this app allowed to touch the camera/mic right now" question, but not
    // CPU sleep: without a wake lock, Doze can still defer/throttle the UDP sends
    // and capture callbacks once the screen's been off for a while. Held for the
    // whole time this service is alive, released in onDestroy.
    private var wakeLock: PowerManager.WakeLock? = null

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

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MeshDrive:vehicle").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    // Android 14+ requires actually HOLDING the runtime permission matching each
    // declared foreground service type at the moment startForeground() is called
    // for that type, or the call throws outright - confirmed on real hardware as a
    // real crash ("problem with the phone"-style): denying just the CAMERA
    // permission crashed the service on start, since the manifest declares
    // camera+microphone+location unconditionally and the plain 2-arg
    // startForeground(id, notification) uses every type the manifest declares
    // regardless of what's actually granted. Building the type set from what's
    // actually held right now - denied ones simply aren't requested, exactly like
    // VideoSender/AudioSender/GPS already no-op gracefully when their own
    // permission check fails - lets the service start fine either way; it'll pick
    // up newly-granted permissions next time this is called (every time
    // VehicleActivity (re)starts it).
    private fun grantedForegroundServiceTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return types
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, channelId)
            .setContentTitle(getString(nu.bruijn.rcvehicle.R.string.app_name))
            .setContentText(getString(nu.bruijn.rcvehicle.R.string.vehicle_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, grantedForegroundServiceTypes())
        } else {
            startForeground(1, notification)
        }
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
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
}
