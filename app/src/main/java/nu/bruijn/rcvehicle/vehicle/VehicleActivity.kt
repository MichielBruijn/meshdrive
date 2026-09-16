package nu.bruijn.rcvehicle.vehicle

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import nu.bruijn.rcvehicle.R
import nu.bruijn.rcvehicle.audio.AudioPacket
import nu.bruijn.rcvehicle.audio.AudioReceiver
import nu.bruijn.rcvehicle.audio.AudioSender
import nu.bruijn.rcvehicle.common.InternetNetworkBinder
import nu.bruijn.rcvehicle.common.QrCode
import nu.bruijn.rcvehicle.common.YggdrasilAddress
import nu.bruijn.rcvehicle.common.showPermissionDeniedIfAny
import nu.bruijn.rcvehicle.control.ControlFrame
import nu.bruijn.rcvehicle.control.ControlLink
import nu.bruijn.rcvehicle.control.PairingSecret
import nu.bruijn.rcvehicle.control.VehiclePairing
import nu.bruijn.rcvehicle.mode.ModePrefs
import nu.bruijn.rcvehicle.video.VideoPacket
import nu.bruijn.rcvehicle.video.VideoQuality
import nu.bruijn.rcvehicle.video.VideoSender
import nu.bruijn.rcvehicle.yggdrasil.YggdrasilController
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
        private const val DEFAULT_MAX_SPEED_FOR_FULL_STEER_LIMIT_KMH = 10f
        private const val DEFAULT_MAX_STEER_REDUCTION_FRACTION = 0.5f
    }

    private var service: VehicleForegroundService? = null
    private var bound = false
    private val tickHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null
    private val controlLink = ControlLink()
    // Loaded once per process, not per onCreate() - PairingSecret.load() itself
    // is idempotent (same file), but there's no reason to touch disk twice if
    // this Activity gets recreated.
    private val pairingSecret by lazy { PairingSecret.load(applicationContext) }
    private val videoSender = VideoSender(this)
    private val audioSender = AudioSender(android.media.MediaRecorder.AudioSource.CAMCORDER)

    // Controller's talk-back mic (two-way audio, per direct request) - doesn't need
    // to know the controller's address to listen, same as ControlLink.listen(), so
    // it can just run for this screen's whole lifetime rather than being tied to
    // maybeStartVideo() the way the outgoing audioSender above is.
    private val controllerVoiceReceiver = AudioReceiver()

    // Who video/audio are currently pointed at - null until the first ever
    // controller connects. Re-checked (not a one-time latch) so a later
    // change of control owner (ControlLink.listen()'s ownership lock) also
    // re-targets the outgoing streams - see maybeStartVideo()'s doc.
    private var currentVideoControllerAddress: InetAddress? = null
    private var lastRemoteControlAtMs = 0L

    // GPS is shared by two independent features - the debug speed/GPS overlay
    // (telemetryEnabled, sent to the controller) and the speed-based steering
    // limiter below (speedLimitEnabled, used locally, nothing to do with the
    // overlay being on) - either one alone should start it, and it only stops once
    // neither wants it, so this phone isn't burning battery on fixes nobody needs.
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null

    // Written from the control-link receive loop (background), read from the same
    // place plus the LocationListener callback (main thread) - @Volatile so a
    // toggle is never missed/stale across those threads.
    @Volatile private var telemetryEnabled = false
    @Volatile private var speedLimitEnabled = false

    // Tunable from the controller's debug sliders (ControlFrame.SteerLimitConfig) -
    // see applySpeedSteerLimit() for what these actually do.
    @Volatile private var maxSpeedForFullSteerLimitKmh = DEFAULT_MAX_SPEED_FOR_FULL_STEER_LIMIT_KMH
    @Volatile private var maxSteerReductionFraction = DEFAULT_MAX_STEER_REDUCTION_FRACTION

    // Read from the control-handling coroutine (Dispatchers.IO), written from the
    // LocationListener callback (main thread, per requestLocationUpdates below).
    @Volatile private var currentSpeedKmh = 0f

    private fun setTelemetryEnabled(enabled: Boolean) {
        telemetryEnabled = enabled
        updateLocationTracking()
    }

    /**
     * Speed-based steering limit (replaces the servo tester's own throttle-position-
     * based one, "poor man's" version, since throttle position is a weak proxy for
     * actual speed - e.g. full throttle from a dead stop shouldn't limit steering
     * the way full throttle already at speed should). See applySpeedSteerLimit()
     * for the actual math and setSteerLimitEnabled(false) below for why the
     * firmware's own version needs to stay off while this is active - running both
     * would double-limit.
     */
    private fun setSpeedLimitEnabled(enabled: Boolean) {
        speedLimitEnabled = enabled
        updateLocationTracking()
    }

    private fun updateLocationTracking() {
        val manager = locationManager ?: (getSystemService(Context.LOCATION_SERVICE) as LocationManager).also { locationManager = it }
        if (!telemetryEnabled && !speedLimitEnabled) {
            locationListener?.let { manager.removeUpdates(it) }
            locationListener = null
            currentSpeedKmh = 0f
            return
        }
        if (locationListener != null) return // already tracking, nothing to do
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val listener = LocationListener { location: Location ->
            // location.speed is m/s and only meaningful once the provider actually
            // has a fix with a real velocity estimate (GPS does once moving) - a
            // stale/first fix can report 0 even while moving, but that just shows
            // as "0 km/h"/no limiting for a moment rather than anything misleading.
            val speedKmh = location.speed * 3.6f
            currentSpeedKmh = speedKmh
            if (telemetryEnabled) {
                controlLink.sendTelemetry(true, speedKmh, location.latitude, location.longitude)
            }
        }
        locationListener = listener
        try {
            // minTime is a floor, not a guarantee - most GNSS chips still cap
            // themselves around 1Hz regardless of what's requested here, but
            // asking for 1000ms ourselves was needlessly capping the ones that
            // can do better. 200ms just stops being the bottleneck.
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 200L, 0f, listener, Looper.getMainLooper())
        } catch (_: Exception) {
            // No GPS provider on this device/emulator - nothing to fall back to
            // for a moving-vehicle speed reading, network location is far too
            // coarse/slow for that.
        }
    }

    /**
     * Scales steer deflection down as speed increases, symmetric around center -
     * the app-side, real-speed equivalent of the firmware's own
     * applySteerOutput()/STEERING_LIMIT. maxSpeedForFullSteerLimitKmh/
     * maxSteerReductionFraction default to a starting point (full steering near a
     * stop, keeping half of range at/above a brisk 10 km/h) and are tuned live
     * from the controller's debug sliders (ControlFrame.SteerLimitConfig).
     */
    private fun applySpeedSteerLimit(steer: Float, speedKmh: Float): Float {
        val speedFraction = (speedKmh / maxSpeedForFullSteerLimitKmh).coerceIn(0f, 1f)
        val scale = 1f - speedFraction * maxSteerReductionFraction
        return steer * scale
    }

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

    // Catches the ESP32 being plugged in WHILE already on this screen (the manifest's
    // USB_DEVICE_ATTACHED filter only launches a fresh MainActivity, which doesn't
    // help if VehicleActivity is already in front) - and, just as importantly, being
    // unplugged. Confirmed on real hardware as a real gap: nothing previously
    // listened for DETACHED at all, so pulling the cable left port non-null and the
    // "Connected" UI stuck showing green while writes silently failed one by one,
    // instead of the app immediately reflecting reality and going quiet. Stopping
    // here (rather than waiting for the next reconnect attempt to notice) is what
    // makes the eventual reconnect clean instead of resuming into whatever half-
    // torn-down state a stale port was left in.
    private val usbAttachReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED -> service?.connectUsb()
                android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED -> service?.usbServoBridge?.stop()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Deliberately NOT forcing the screen to stay on (FLAG_KEEP_SCREEN_ON) here
        // anymore: letting this phone's own timeout lock it is fine, and per direct
        // request even desirable - mounted on the vehicle, unattended, a locked
        // screen means nobody can just pick it up and stop the app without
        // unlocking it first. This used to be needed because the display timeout
        // locking the phone made Android revoke camera access from the now-
        // backgrounded app (confirmed on real hardware: onError fired "camera
        // error: 3" - ERROR_CAMERA_DISABLED - right on schedule with the screen
        // timeout) - fixed properly since by declaring VehicleForegroundService's
        // foregroundServiceType as camera+microphone (see its manifest entry and
        // doc), which is what actually keeps a locked/backgrounded app's camera and
        // mic access alive, plus a wake lock so the CPU doesn't doze in the
        // meantime.
        setContentView(R.layout.activity_vehicle)

        ContextCompat.registerReceiver(
            this,
            usbAttachReceiver,
            android.content.IntentFilter().apply {
                addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
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

        // Capped to the last N lines - an unbounded log view (wrap_content, no
        // scroll) pushed the rest of this screen off-screen within seconds at
        // the sliders/control-frame log's own rate, confirmed on real hardware.
        val maxLogLines = 20
        fun log(line: String) = runOnUiThread {
            Log.d("RCDebug", line)
            val lines = (logView.text.toString() + line + "\n").lines()
            logView.text = lines.takeLast(maxLogLines + 1).joinToString("\n")
        }

        val neededPermissions = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (neededPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toTypedArray(), 1)
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

        // MainActivity already started the embedded YggdrasilVpnService (with VPN
        // consent granted) before launching this screen - this just polls for the
        // address it ends up with, since startForegroundService() returning doesn't
        // mean the mesh node has actually come up yet (config load, TUN establish,
        // first startJSON() all happen asynchronously inside the service). Re-run
        // from onResume (recheckYggdrasil below) so coming back to this screen (e.g.
        // after granting VPN consent) picks the address up without a relaunch.
        fun checkYggdrasil() {
            lifecycleScope.launch {
                val address = withTimeoutOrNull(5000) {
                    var found = YggdrasilController.address()
                    while (found == null) {
                        delay(200)
                        found = YggdrasilController.address()
                    }
                    found
                }
                if (address != null) {
                    // The full pairing string (not just the address) - debug mode's
                    // manual-paste path (ControllerActivity) needs the secret too now,
                    // same as the QR below.
                    textOwnAddress.text = "Own pairing string: ${VehiclePairing.encode(address, pairingSecret)}"
                    textYggdrasilStatus.text = ""
                    buttonOpenYggdrasil.visibility = View.GONE
                    imageQr.setImageBitmap(QrCode.encode(VehiclePairing.encode(address, pairingSecret)))
                } else {
                    textYggdrasilStatus.text = "Starting Yggdrasil mesh…"
                    buttonOpenYggdrasil.text = getString(R.string.vehicle_open_yggdrasil)
                    buttonOpenYggdrasil.visibility = View.VISIBLE
                    buttonOpenYggdrasil.setOnClickListener {
                        // Confirmed on real hardware as a real bug: this used to
                        // just re-poll for an address that will never appear if
                        // YggdrasilController.start() never actually succeeded in
                        // the first place - no way to recover short of fully
                        // closing and reopening the app. Actually retries
                        // starting the mesh now (consent is already granted by
                        // this point - MainActivity's own flow already went
                        // through VpnService.prepare() before ever launching
                        // this screen), then re-polls as before.
                        lifecycleScope.launch {
                            val candidates = ModePrefs(applicationContext).yggdrasilPeerCandidates.first()
                            YggdrasilController.start(this@VehicleActivity, candidates)
                            checkYggdrasil()
                        }
                    }
                }
            }
        }
        recheckYggdrasil = ::checkYggdrasil
        checkYggdrasil()

        val textYggdrasilPeers = findViewById<TextView>(R.id.textYggdrasilPeers)
        lifecycleScope.launch {
            while (isActive) {
                val active = YggdrasilController.activePeer()
                val statuses = YggdrasilController.peerStatuses()
                textYggdrasilPeers.text = if (statuses.isEmpty()) {
                    ""
                } else {
                    statuses.joinToString("\n") { s ->
                        val marker = if (s.uri == active) "*" else " "
                        val latency = s.latencyMs?.let { "${it}ms" } ?: "unreachable"
                        "$marker ${s.uri} - $latency"
                    }
                }
                delay(5000)
            }
        }

        videoSender.onError = { msg -> log("video: $msg") }

        controllerVoiceReceiver.onError = { msg -> log("controller voice: $msg") }
        try {
            controllerVoiceReceiver.start(AudioPacket.CONTROLLER_TO_VEHICLE_PORT, pairingSecret)
        } catch (e: Exception) {
            log("controller voice listen failed: ${e::class.simpleName}: ${e.message}")
        }

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
                    controlLink.listen(ControlLink.PORT, pairingSecret) { decoded, senderAddress ->
                        when (decoded) {
                            is ControlFrame.Decoded.Control -> {
                                // Deliberately NOT logged per-frame (confirmed on real
                                // hardware as a real bug: at the controller's 20-33Hz
                                // tick rate this filled the log view within seconds,
                                // pushing everything below it off-screen) - the sliders
                                // just below already give live steer/throttle feedback.
                                lastRemoteControlAtMs = System.currentTimeMillis()
                                val steer = if (speedLimitEnabled) applySpeedSteerLimit(decoded.steer, currentSpeedKmh) else decoded.steer
                                service?.activeLink?.sendControl(steer, decoded.throttle)
                                // Sliders track whatever is actually being sent to the
                                // servo, remote or local - a live readout, not just a
                                // local debug input (they're locked out from sending
                                // while a remote controller is active anyway, see the
                                // tick loop below, so moving them here doesn't fight it).
                                runOnUiThread {
                                    seekSteer.progress = ((steer + 1f) * 100).toInt()
                                    seekThrottle.progress = ((decoded.throttle + 1f) * 100).toInt()
                                }
                                maybeStartVideo(senderAddress, ::log)
                            }
                            is ControlFrame.Decoded.SteerLimit -> {
                                log("steer limit (speed-based): ${decoded.enabled}")
                                setSpeedLimitEnabled(decoded.enabled)
                                // Always keep the firmware's own throttle-position-based
                                // limiter off - it would double-limit alongside this one.
                                service?.activeLink?.setSteerLimitEnabled(false)
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
                            is ControlFrame.Decoded.TelemetryEnable -> {
                                log("telemetry: ${decoded.enabled}")
                                setTelemetryEnabled(decoded.enabled)
                            }
                            is ControlFrame.Decoded.Telemetry -> {
                                // Vehicle -> controller only, never sent to the vehicle.
                            }
                            is ControlFrame.Decoded.SteerLimitConfig -> {
                                log("steer limit config: max ${decoded.maxSpeedKmh} km/h, reduce ${(decoded.reductionFraction * 100).toInt()}%")
                                maxSpeedForFullSteerLimitKmh = decoded.maxSpeedKmh
                                maxSteerReductionFraction = decoded.reductionFraction
                            }
                            is ControlFrame.Decoded.Ping, is ControlFrame.Decoded.Pong -> {
                                // Handled transparently inside ControlLink.listen() - never reaches here.
                            }
                            is ControlFrame.Decoded.Busy -> {
                                // Vehicle -> controller only, never sent to the vehicle.
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
                val internet = withContext(Dispatchers.IO) { getInternetNetwork() }
                val release = withContext(Dispatchers.IO) { FirmwareUpdater.fetchLatestRelease(internet) }
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
                val internet = withContext(Dispatchers.IO) { getInternetNetwork() }
                val bytes = withContext(Dispatchers.IO) { FirmwareUpdater.download(release.downloadUrl, internet) }
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

    /**
     * Null on timeout (best-effort - callers just fall back to whatever the phone's
     * current default route is, matching the old unbound-client behavior). See
     * InternetNetworkBinder's doc for why this exists at all: joining the ESP32's
     * own AP for the wifi update path below can silently become the phone's default
     * route, breaking anything that needs *real* internet (the GitHub check/download)
     * with no obvious reason why from the error alone.
     */
    private suspend fun getInternetNetwork() = withTimeoutOrNull(10_000) {
        InternetNetworkBinder(applicationContext).observe()
            .filterIsInstance<InternetNetworkBinder.State.Available>()
            .first().network
    }

    private fun maybeStartVideo(controllerAddress: InetAddress, log: (String) -> Unit) {
        if (currentVideoControllerAddress == controllerAddress) return
        val isRetarget = currentVideoControllerAddress != null
        currentVideoControllerAddress = controllerAddress

        if (isRetarget) {
            // A different controller than the one video/audio are currently
            // pointed at just took ownership (ControlLink.listen()'s lock) -
            // confirmed on real hardware as a real bug without this: video/
            // audio kept streaming to whichever controller connected FIRST
            // for the rest of the session (controls worked for the new one,
            // nothing else did). See VideoSender/AudioSender.retarget()'s doc
            // for why this re-points the running pipelines instead of
            // restarting them.
            log("re-targeting video/audio to new controller: ${controllerAddress.hostAddress}")
            videoSender.retarget(controllerAddress.hostAddress!!)
            audioSender.retarget(controllerAddress.hostAddress!!)
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            log("video: CAMERA permission not granted, skipping")
            return
        }
        log("starting video to ${controllerAddress.hostAddress}")
        // Starts at LOW, matching the controller's own default (ControllerDriveActivity) -
        // confirmed on real hardware as a real bug starting at HIGH: the controller's
        // corrective "sync to whatever quality is actually shown" Quality message
        // (sent right after connecting) can race against - or simply get lost
        // relative to - the vehicle's very first video frames, meaning a session
        // could visibly start (and briefly stay) at full HIGH bandwidth/battery cost
        // despite the controller UI showing Low the whole time. Starting at LOW
        // itself needs no such correction to arrive at all - the controller's Quality
        // message only needs to do anything when someone actually picks something
        // other than Low. VideoSender.openCamera()'s onConfigureFailed still falls
        // back further if even this gets rejected outright by the camera HAL.
        videoSender.start(controllerAddress.hostAddress!!, VideoPacket.PORT, pairingSecret, VideoQuality.LOW)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            audioSender.onError = { msg -> log("audio: $msg") }
            audioSender.start(controllerAddress.hostAddress!!, AudioPacket.VEHICLE_TO_CONTROLLER_PORT, pairingSecret)
        } else {
            log("audio: RECORD_AUDIO permission not granted, skipping")
        }
    }

    override fun onResume() {
        super.onResume()
        recheckYggdrasil?.invoke()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        showPermissionDeniedIfAny(grantResults)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbAttachReceiver) }
        tickRunnable?.let { tickHandler.removeCallbacks(it) }
        controlLink.close()
        videoSender.stop()
        audioSender.stop()
        controllerVoiceReceiver.stop()
        locationListener?.let { locationManager?.removeUpdates(it) }
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}
