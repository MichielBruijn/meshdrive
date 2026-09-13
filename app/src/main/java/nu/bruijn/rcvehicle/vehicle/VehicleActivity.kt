package nu.bruijn.rcvehicle.vehicle

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import nu.bruijn.rcvehicle.R
import nu.bruijn.rcvehicle.common.QrCode
import nu.bruijn.rcvehicle.common.YggdrasilAddress
import nu.bruijn.rcvehicle.control.ControlFrame
import nu.bruijn.rcvehicle.control.ControlLink
import nu.bruijn.rcvehicle.mode.ModePrefs
import nu.bruijn.rcvehicle.video.VideoPacket
import nu.bruijn.rcvehicle.video.VideoQuality
import nu.bruijn.rcvehicle.video.VideoSender
import java.net.InetAddress

/**
 * Debug UI: top half connects to the ESP32 (USB-OTG serial, primary - see
 * UsbServoBridge's doc; wifi kept as a fallback via ServoBridge) and drives it
 * with two sliders. Bottom half (UDP control link over Yggdrasil, fase 4) listens
 * for the controller and relays whatever it receives into the SAME
 * `service.activeLink` - so with both sections connected at once this is already
 * a real end-to-end controller-phone -> vehicle-phone -> ESP32 path. Video starts
 * automatically the moment the first control packet arrives, since that's how
 * this side learns the controller's Yggdrasil address (see ControlLink.listen's
 * doc) - no separate video-connect step.
 *
 * See ControlLink's/VideoSender's docs for why these are plain UDP sockets instead
 * of the originally-planned WebRTC data channel/video track.
 */
class VehicleActivity : AppCompatActivity() {

    companion object {
        private const val YGGDRASIL_PACKAGE = "eu.neilalexander.yggdrasil"
    }

    private var service: VehicleForegroundService? = null
    private var bound = false
    private val tickHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null
    private val controlLink = ControlLink()
    private val videoSender = VideoSender(this)
    private var videoStarted = false
    private var lastRemoteControlAtMs = 0L

    // Set once onServiceConnected below has a service to attach it to - see setUsbButtonConnected() in onCreate.
    private var onUsbConnectedChanged: ((Boolean) -> Unit)? = null

    // Set in onCreate - re-run from onResume so coming back from actually opening
    // Yggdrasil (buttonOpenYggdrasil) picks up the result automatically.
    private var recheckYggdrasil: (() -> Unit)? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as VehicleForegroundService.LocalBinder
            service = localBinder.service
            bound = true
            service?.onStatus = { status ->
                runOnUiThread { findViewById<TextView>(R.id.textStatus).text = status }
            }
            service?.usbServoBridge?.onConnected = { connected ->
                runOnUiThread { onUsbConnectedChanged?.invoke(connected) }
            }
            // No reason to make the driver tap "Connect via USB cable" themselves - if
            // the ESP32 is already plugged in when this screen opens (the common case:
            // the manifest's USB_DEVICE_ATTACHED filter already launched the mode picker
            // for exactly this reason), just connect immediately. usbServoBridge.start()
            // already handles "no device found" gracefully, so this is harmless if
            // nothing's plugged in yet.
            service?.connectUsb()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    // Catches the ESP32 being plugged in WHILE already on this screen - the manifest's
    // USB_DEVICE_ATTACHED filter only launches a fresh MainActivity, which doesn't help
    // if VehicleActivity is already in front.
    private val usbAttachReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                service?.connectUsb()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nobody touches this phone's own screen while driving - it's mounted on
        // the vehicle, the driver is looking at the controller phone instead.
        // Without this, the display timeout locks it, Android revokes camera
        // access from the now-backgrounded app, and VideoSender dies mid-stream
        // (confirmed on real hardware as onError firing "camera error: 3" -
        // ERROR_CAMERA_DISABLED - after a delay matching the screen timeout).
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_vehicle)

        ContextCompat.registerReceiver(
            this,
            usbAttachReceiver,
            android.content.IntentFilter(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Debug mode (MainActivity's checkbox) hides raw addresses/logs/the local debug
        // sliders/the wifi fallback - none of that is needed for normal day-to-day driving,
        // it's here for diagnosing problems. A one-shot read is enough: changing the toggle
        // requires going back to the mode picker anyway, there's no live-update need.
        val debugSection = findViewById<View>(R.id.debugSection)
        lifecycleScope.launch {
            debugSection.visibility = if (ModePrefs(applicationContext).debugEnabled.first()) View.VISIBLE else View.GONE
        }

        val editIp = findViewById<EditText>(R.id.editEspIp)
        val seekSteer = findViewById<SeekBar>(R.id.seekSteer)
        val seekThrottle = findViewById<SeekBar>(R.id.seekThrottle)
        val textOwnAddress = findViewById<TextView>(R.id.textOwnYggdrasilAddress)
        val logView = findViewById<TextView>(R.id.textRtcLog)

        fun log(line: String) = runOnUiThread {
            Log.d("RCDebug", line)
            logView.append(line + "\n")
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
        }

        val buttonConnectUsb = findViewById<Button>(R.id.buttonConnectUsb)
        val defaultUsbButtonTint = buttonConnectUsb.backgroundTintList
        val defaultUsbButtonText = buttonConnectUsb.text
        val usbConnectedTint = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2E7D32"))

        fun setUsbButtonConnected(connected: Boolean) {
            buttonConnectUsb.backgroundTintList = if (connected) usbConnectedTint else defaultUsbButtonTint
            buttonConnectUsb.text = if (connected) getString(R.string.vehicle_usb_connected) else defaultUsbButtonText
        }
        onUsbConnectedChanged = ::setUsbButtonConnected

        buttonConnectUsb.setOnClickListener {
            service?.connectUsb()
        }

        setUpFirmwareUpdateUi()

        findViewById<Button>(R.id.buttonConnect).setOnClickListener {
            service?.connectToEsp(editIp.text.toString().trim())
        }

        val imageQr = findViewById<ImageView>(R.id.imageYggdrasilQr)
        val textYggdrasilStatus = findViewById<TextView>(R.id.textYggdrasilStatus)
        val buttonOpenYggdrasil = findViewById<Button>(R.id.buttonOpenYggdrasil)

        // Checks (not auto-launches) - forcing an app switch to Yggdrasil/F-Droid every
        // single time this screen opened, e.g. right after a reboot before Yggdrasil was
        // back up yet, was disruptive during repeated testing. Now just explains the
        // situation and offers a button; re-run from onResume (recheckYggdrasil below) so
        // coming back from actually opening Yggdrasil picks up the result without needing
        // to relaunch this screen.
        fun checkYggdrasil() {
            lifecycleScope.launch {
                // getLaunchIntentForPackage() returns null both when Yggdrasil isn't running
                // AND when it isn't installed at all - checked explicitly so an actually-
                // missing install gets a real prompt (F-Droid, where the plan's pairing step
                // assumes it's distributed) instead of just repeating "not found" forever.
                if (!isPackageInstalled(YGGDRASIL_PACKAGE)) {
                    textYggdrasilStatus.text = "Yggdrasil app not installed"
                    buttonOpenYggdrasil.text = "Install from F-Droid"
                    buttonOpenYggdrasil.visibility = View.VISIBLE
                    buttonOpenYggdrasil.setOnClickListener {
                        runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/$YGGDRASIL_PACKAGE/")))
                        }
                    }
                    return@launch
                }
                val address = withContext(Dispatchers.IO) { YggdrasilAddress.detect() }
                if (address != null) {
                    textOwnAddress.text = "Own Yggdrasil address: $address"
                    textYggdrasilStatus.text = ""
                    buttonOpenYggdrasil.visibility = View.GONE
                    imageQr.setImageBitmap(QrCode.encode(address))
                } else {
                    // There's no public API to enable Yggdrasil's VPN from another app
                    // (its own state broadcast is LocalBroadcastManager-only, checked in
                    // its source - not visible outside its own process), so a button to
                    // bring its own UI to the front is the best available shortcut.
                    textYggdrasilStatus.text = "Yggdrasil not running - open it and enable the VPN"
                    buttonOpenYggdrasil.text = getString(R.string.vehicle_open_yggdrasil)
                    buttonOpenYggdrasil.visibility = View.VISIBLE
                    buttonOpenYggdrasil.setOnClickListener {
                        packageManager.getLaunchIntentForPackage(YGGDRASIL_PACKAGE)?.let { startActivity(it) }
                    }
                }
            }
        }
        recheckYggdrasil = ::checkYggdrasil
        checkYggdrasil()

        videoSender.onError = { msg -> log("video: $msg") }

        val buttonListen = findViewById<Button>(R.id.buttonListen)
        val defaultListenButtonTint = buttonListen.backgroundTintList
        val defaultListenButtonText = buttonListen.text

        fun setListenButtonUp(up: Boolean) {
            buttonListen.backgroundTintList = if (up) usbConnectedTint else defaultListenButtonTint
            buttonListen.text = if (up) getString(R.string.vehicle_listening) else defaultListenButtonText
        }

        fun startListening() {
            controlLink.close()
            setListenButtonUp(false)
            logView.text = ""
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    controlLink.listen(ControlLink.PORT) { decoded, senderAddress ->
                        when (decoded) {
                            is ControlFrame.Decoded.Control -> {
                                log("control #${decoded.seq}: steer=${decoded.steer} throttle=${decoded.throttle}")
                                lastRemoteControlAtMs = System.currentTimeMillis()
                                service?.activeLink?.sendControl(decoded.steer, decoded.throttle)
                                // Sliders track whatever is actually being sent to the
                                // servo, remote or local - a live readout, not just a
                                // local debug input (they're locked out from sending
                                // while a remote controller is active anyway, see the
                                // tick loop below, so moving them here doesn't fight it).
                                runOnUiThread {
                                    seekSteer.progress = ((decoded.steer + 1f) * 100).toInt()
                                    seekThrottle.progress = ((decoded.throttle + 1f) * 100).toInt()
                                }
                                maybeStartVideo(senderAddress, ::log)
                            }
                            is ControlFrame.Decoded.SteerLimit -> {
                                log("steer limit: ${decoded.enabled}")
                                service?.activeLink?.setSteerLimitEnabled(decoded.enabled)
                            }
                            is ControlFrame.Decoded.Quality -> {
                                val quality = VideoQuality.fromIndex(decoded.presetIndex)
                                log("quality: ${quality.label}")
                                videoSender.setQuality(quality)
                            }
                            is ControlFrame.Decoded.Camera -> {
                                log("camera: ${if (decoded.useFront) "front (rear view)" else "back"}")
                                videoSender.switchCamera(decoded.useFront)
                            }
                            is ControlFrame.Decoded.Ping, is ControlFrame.Decoded.Pong -> {
                                // Handled transparently inside ControlLink.listen() - never reaches here.
                            }
                        }
                    }
                    log("listening for UDP control on port ${ControlLink.PORT}")
                    runOnUiThread { setListenButtonUp(true) }
                } catch (e: Exception) {
                    log("listen failed: ${e::class.simpleName}: ${e.message}")
                }
            }
        }

        // Starts as soon as the screen opens - no reason to make the driver tap a
        // button first, this doesn't depend on the ESP32 connection above (the
        // controller can connect and the video can start before/without a servo
        // link). The button stays wired to the same function as a manual
        // restart/clear-log, e.g. after fiddling with the ESP32 connection.
        startListening()
        buttonListen.setOnClickListener { startListening() }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Snap back to center on release, like a real self-centering joystick.
                seekBar?.progress = 100
            }
        }
        seekSteer.setOnSeekBarChangeListener(listener)
        seekThrottle.setOnSeekBarChangeListener(listener)

        // Send on a fixed 20Hz tick regardless of whether the value changed - matches
        // the plan's eventual data-channel design (section 4). Sending only on
        // SeekBar's own onProgressChanged events was the earlier bug: touch-move events
        // don't fire at a guaranteed steady rate, so gaps over 300ms kept tripping
        // ServoBridge's own failsafe mid-drag, snapping to center and back - that's the
        // jitter. A steady heartbeat is what the failsafe is designed to key off.
        //
        // Skipped entirely while a remote controller has sent something in the last
        // second: these local sliders sit at rest (0,0) whenever nobody is touching
        // them, and kept firing regardless - racing with the fase-4 UDP relay below
        // and stomping on its actual values every ~30ms. That was a second, separate
        // cause of jittery servo movement (this one visible even while the remote
        // controller held perfectly still), on top of the USB write-ordering one.
        val textLinkStatus = findViewById<TextView>(R.id.textLinkStatus)
        var lastLinkStatusText: String? = null

        tickRunnable = object : Runnable {
            override fun run() {
                if (System.currentTimeMillis() - lastRemoteControlAtMs > 1000) {
                    val steer = (seekSteer.progress - 100) / 100f
                    val throttle = (seekThrottle.progress - 100) / 100f
                    service?.activeLink?.sendControl(steer, throttle)
                }
                // Liveness indicator (Yggdrasil's own app dropping its VPN when wifi turns
                // off, with no automatic fallback to mobile data on some phones, is exactly
                // the kind of silent failure this needs to surface) - a controller that was
                // never seen reads differently from one that WAS driving and then went quiet,
                // since the latter usually means the link just died, not "not started yet".
                val sinceLastControl = System.currentTimeMillis() - lastRemoteControlAtMs
                val text = when {
                    lastRemoteControlAtMs == 0L -> "Waiting for controller…"
                    sinceLastControl > 2000 -> "⚠ Controller signal lost (${sinceLastControl / 1000}s)"
                    else -> "Controller connected"
                }
                if (text != lastLinkStatusText) {
                    lastLinkStatusText = text
                    textLinkStatus.text = text
                }
                tickHandler.postDelayed(this, 30) // ~33Hz, matching the browser UI's own throttle rate
            }
        }
        tickHandler.post(tickRunnable!!)

        val intent = Intent(this, VehicleForegroundService::class.java)
        startForegroundService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    /**
     * "Check for update" reads the running version over the same USB cable as normal
     * driving (UsbServoBridge.checkFirmwareVersion(), needs the board to answer
     * "GETVERSION" - which is why this checks service?.usbServoBridge specifically,
     * not whatever activeLink happens to be). "Install update" goes over wifi instead
     * (WifiFirmwareUpdater) - see its doc for why the two use different transports.
     */
    private fun setUpFirmwareUpdateUi() {
        val buttonCheckUpdate = findViewById<Button>(R.id.buttonCheckUpdate)
        val buttonInstallUpdate = findViewById<Button>(R.id.buttonInstallUpdate)
        val textUpdateStatus = findViewById<TextView>(R.id.textUpdateStatus)
        var pendingRelease: FirmwareUpdater.Release? = null

        buttonCheckUpdate.setOnClickListener {
            buttonCheckUpdate.isEnabled = false
            buttonInstallUpdate.visibility = View.GONE
            textUpdateStatus.text = "Checking board version…"
            lifecycleScope.launch {
                val currentVersion = withContext(Dispatchers.IO) {
                    suspendCancellableCoroutine<String?> { cont ->
                        service?.usbServoBridge?.checkFirmwareVersion { cont.resume(it) } ?: cont.resume(null)
                    }
                }
                if (currentVersion == null) {
                    textUpdateStatus.text = "Could not read board version - connect via USB first"
                    buttonCheckUpdate.isEnabled = true
                    return@launch
                }
                textUpdateStatus.text = "Board is v$currentVersion, checking GitHub…"
                val release = withContext(Dispatchers.IO) { FirmwareUpdater.fetchLatestRelease() }
                buttonCheckUpdate.isEnabled = true
                when {
                    release == null -> textUpdateStatus.text = "Board v$currentVersion - could not reach GitHub"
                    FirmwareUpdater.isNewer(release.version, currentVersion) -> {
                        pendingRelease = release
                        textUpdateStatus.text = "Update available: v${release.version} (board is v$currentVersion)"
                        buttonInstallUpdate.visibility = View.VISIBLE
                    }
                    else -> textUpdateStatus.text = "Board v$currentVersion - up to date"
                }
            }
        }

        // Installing goes over wifi (WifiFirmwareUpdater), not the USB cable checking used
        // above - see its doc for why: the raw USB-serial link corrupted this specific bulk
        // transfer on every attempt, confirmed via an MD5 mismatch even after a full,
        // uninterrupted transfer, despite several rounds of fixes. Requires the phone to
        // already be joined to the ESP32's own wifi network (same one-time manual join the
        // wifi-fallback control path above needs).
        buttonInstallUpdate.setOnClickListener {
            val release = pendingRelease ?: return@setOnClickListener
            buttonInstallUpdate.isEnabled = false
            buttonCheckUpdate.isEnabled = false
            textUpdateStatus.text = "Downloading v${release.version}…"
            lifecycleScope.launch {
                val bytes = withContext(Dispatchers.IO) { FirmwareUpdater.download(release.downloadUrl) }
                if (bytes == null) {
                    textUpdateStatus.text = "Download failed"
                    buttonInstallUpdate.isEnabled = true
                    buttonCheckUpdate.isEnabled = true
                    return@launch
                }
                textUpdateStatus.text = "Joining ESP32 wifi…"
                val network = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(15_000) {
                        EspWifiNetworkBinder(applicationContext).observe()
                            .filterIsInstance<EspWifiNetworkBinder.State.Available>()
                            .first().network
                    }
                }
                if (network == null) {
                    textUpdateStatus.text = "Could not reach ESP32 wifi - join its network first (see fallback section)"
                    buttonInstallUpdate.isEnabled = true
                    buttonCheckUpdate.isEnabled = true
                    return@launch
                }
                val espIp = findViewById<EditText>(R.id.editEspIp).text.toString().trim().ifEmpty { "192.168.4.1" }
                textUpdateStatus.text = "Uploading 0%…"
                val (success, message) = withContext(Dispatchers.IO) {
                    WifiFirmwareUpdater().upload(network, espIp, bytes) { written, total ->
                        runOnUiThread { textUpdateStatus.text = "Uploading ${written * 100 / total}%…" }
                    }
                }
                textUpdateStatus.text = if (success) "v${release.version} installed - $message" else "Update failed: $message"
                buttonCheckUpdate.isEnabled = true
                if (success) {
                    pendingRelease = null
                } else {
                    buttonInstallUpdate.isEnabled = true
                }
            }
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean =
        runCatching { packageManager.getPackageInfo(packageName, 0) }.isSuccess

    private fun maybeStartVideo(controllerAddress: InetAddress, log: (String) -> Unit) {
        if (videoStarted) return
        videoStarted = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            log("video: CAMERA permission not granted, skipping")
            return
        }
        log("starting video to ${controllerAddress.hostAddress}")
        videoSender.start(controllerAddress.hostAddress!!, VideoPacket.PORT)
    }

    override fun onResume() {
        super.onResume()
        recheckYggdrasil?.invoke()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbAttachReceiver) }
        tickRunnable?.let { tickHandler.removeCallbacks(it) }
        controlLink.close()
        videoSender.stop()
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}
