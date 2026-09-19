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
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress
import nu.bruijn.rcvehicle.audio.AudioPacket
import nu.bruijn.rcvehicle.audio.AudioReceiver
import nu.bruijn.rcvehicle.audio.AudioSender
import nu.bruijn.rcvehicle.control.ControlFrame
import nu.bruijn.rcvehicle.control.ControlLink
import nu.bruijn.rcvehicle.control.PairingSecret
import nu.bruijn.rcvehicle.video.VideoPacket
import nu.bruijn.rcvehicle.video.VideoQuality
import nu.bruijn.rcvehicle.video.VideoSender
import nu.bruijn.rcvehicle.yggdrasil.ExternalYggdrasilDetector
import nu.bruijn.rcvehicle.yggdrasil.YggdrasilController

/**
 * Hosts both ways of reaching the ESP32 - USB-OTG serial (UsbServoBridge, primary
 * since 2026-09-14) and wifi (ServoBridge, kept as a fallback) - so whichever one
 * is active survives screen-off/backgrounding. `activeLink` is whichever the user
 * last successfully connected; VehicleActivity's control-input tick loop sends
 * through that regardless of which transport it is.
 *
 * Also owns the whole controller-facing side (ControlLink/VideoSender/AudioSender/
 * the controller-voice AudioReceiver, and the network-churn watch loop that
 * refreshes them) - NOT VehicleActivity, even though it used to. Confirmed live as
 * a real bug: `dumpsys activity activities` showed VehicleActivity destroyed (back
 * on MainActivity) while this service and its YggdrasilVpnService kept running
 * fine underneath - which meant every one of those objects, and the network-churn
 * watch loop that's supposed to keep them working, had already been destroyed
 * right along with it. A foreground service is specifically the thing Android
 * guarantees survives backgrounding; an Activity is specifically not - so this is
 * where the actual driving/streaming session has to live, with VehicleActivity
 * reduced to attaching UI callbacks (onLog, onRemoteControl, onListening) to
 * whatever's already running, and re-showing them from onServiceConnected() rather
 * than owning any of it.
 */
class VehicleForegroundService : Service() {

    private companion object {
        // Generous relative to a normal driving pause: ControllerDriveActivity's
        // tick loop sends at a steady ~33Hz the whole time its Activity is
        // actually in the foreground, whether or not the joystick itself is
        // moving - so a real gap this long means the controller app went away
        // (backgrounded without recording, closed, crashed, drove off the
        // mesh), not "the driver paused for a few seconds." See
        // startControllerIdleWatch()'s doc.
        const val CONTROLLER_IDLE_STOP_MS = 20_000L
    }

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

    // --- Controller-facing session: ControlLink/VideoSender/AudioSender, moved
    // here from VehicleActivity - see class doc. ---

    val controlLink = ControlLink()
    val videoSender by lazy { VideoSender(this) }
    val audioSender = AudioSender(android.media.MediaRecorder.AudioSource.CAMCORDER)
    private val controllerVoiceReceiver = AudioReceiver()
    private val pairingSecret by lazy { PairingSecret.load(applicationContext) }

    /** UI hooks, all optional and all safe to leave unset - VehicleActivity attaches
     * them from onServiceConnected() while it's alive and gets fresh values pushed
     * as they happen; none of this session's actual logic depends on any of them
     * being set at all. */
    var onLog: ((String) -> Unit)? = null
    var onRemoteControl: ((steer: Float, throttle: Float) -> Unit)? = null
    var onListeningStateChanged: ((Boolean) -> Unit)? = null

    private fun log(line: String) {
        android.util.Log.d("RCDebug", line)
        onLog?.invoke(line)
    }

    /** Last time any remote controller's Control frame arrived - 0L if never.
     * VehicleActivity's local-slider tick loop reads this to decide whether a
     * remote controller is already driving (in which case the local debug
     * sliders should stay quiet rather than fight it). */
    @Volatile var lastRemoteControlAtMs = 0L
        private set

    private var currentVideoControllerAddress: InetAddress? = null

    // Written from the control-link receive loop (background), read from the same
    // place plus the LocationListener callback (main thread) - @Volatile so a
    // toggle is never missed/stale across those threads.
    @Volatile var telemetryEnabled = false
        private set
    @Volatile var speedLimitEnabled = false
        private set

    // Tunable from the controller's debug sliders (ControlFrame.SteerLimitConfig) -
    // see applySpeedSteerLimit() for what these actually do.
    @Volatile private var maxSpeedForFullSteerLimitKmh = 10f
    @Volatile private var maxSteerReductionFraction = 0.5f

    // Read from the control-handling coroutine (Dispatchers.IO), written from the
    // LocationListener callback (main thread, per requestLocationUpdates below).
    @Volatile private var currentSpeedKmh = 0f

    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null

    fun setTelemetryEnabled(enabled: Boolean) {
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
    fun setSpeedLimitEnabled(enabled: Boolean) {
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

    /**
     * Re-fetched every time driving actually (re)starts - a remote controller
     * newly connecting (maybeStartVideo() below), or the local debug sliders
     * being touched again (VehicleActivity's slider listener) - not just once
     * when the USB port first opens. Confirmed as a real bug without this: the
     * board's calibration (Menu, or the browser's own Settings page) can be
     * changed at any time while the USB cable stays plugged in the whole
     * session, and the app kept using whatever it queried at connect time
     * forever after, silently diverging from the browser (which re-reads
     * current values on every page load) once the board was recalibrated
     * mid-session - and this affects local slider driving exactly as much as
     * a remote controller's, since both go through the same ServoLink. See
     * UsbServoBridge.checkCalibration()'s doc - a no-op if the active link
     * isn't USB serial.
     */
    fun refreshCalibration(log: (String) -> Unit) {
        usbServoBridge.checkCalibration { calibration ->
            if (calibration != null) {
                activeLink?.setCalibration(
                    calibration.steerMin, calibration.steerCenter, calibration.steerMax,
                    calibration.throttleMin, calibration.throttleCenter, calibration.throttleMax,
                )
                log(
                    "using board's calibration: steer=${calibration.steerMin}/${calibration.steerCenter}/${calibration.steerMax} " +
                        "throttle=${calibration.throttleMin}/${calibration.throttleCenter}/${calibration.throttleMax}",
                )
            }
        }
    }

    /**
     * ULOW drops the vehicle's own ambient audio entirely (per direct request,
     * bandwidth/battery) - stops it if currently running, or resumes it
     * (permission-gated, same as maybeStartVideo()'s own initial start) if
     * leaving ULOW finds it stopped. A no-op either way if video was never
     * started at all (no controller address to send it to yet) - the initial
     * start already picks the right state for whatever quality index the
     * controller connects with.
     *
     * Deliberately doesn't touch AudioReceiver/MicSender (the controller's own
     * talk-back mic, controller -> vehicle) - that's a separate, already
     * user-controlled channel (ControllerDriveActivity's Mute button), not
     * part of what "video quality" means here.
     */
    private fun applyAudioForQuality(quality: VideoQuality, log: (String) -> Unit) {
        val controllerAddress = currentVideoControllerAddress ?: return
        if (quality == VideoQuality.ULOW) {
            if (audioSender.isRunning) {
                log("audio: stopping (Ultra Low)")
                audioSender.stop()
            }
        } else if (!audioSender.isRunning) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                log("audio: resuming (left Ultra Low)")
                audioSender.onError = { msg -> log("audio: $msg") }
                audioSender.start(controllerAddress.hostAddress!!, AudioPacket.VEHICLE_TO_CONTROLLER_PORT, pairingSecret)
            }
        }
    }

    private fun maybeStartVideo(controllerAddress: InetAddress, log: (String) -> Unit) {
        if (currentVideoControllerAddress == controllerAddress) return
        val isRetarget = currentVideoControllerAddress != null
        currentVideoControllerAddress = controllerAddress

        refreshCalibration(log)

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

    /** (Re)starts the control-link listener - called once from onCreate(), and
     * again from VehicleActivity's "Listening" button as a manual retry/clear. */
    fun startListening() {
        controlLink.close()
        onListeningStateChanged?.invoke(false)
        serviceScope.launch(Dispatchers.IO) {
            try {
                controlLink.listen(ControlLink.PORT, pairingSecret) { decoded, senderAddress ->
                    when (decoded) {
                        is ControlFrame.Decoded.Control -> {
                            // Deliberately NOT logged per-frame (confirmed on real
                            // hardware as a real bug: at the controller's 20-33Hz
                            // tick rate this filled the log view within seconds,
                            // pushing everything below it off-screen).
                            lastRemoteControlAtMs = System.currentTimeMillis()
                            val steer = if (speedLimitEnabled) applySpeedSteerLimit(decoded.steer, currentSpeedKmh) else decoded.steer
                            activeLink?.sendControl(steer, decoded.throttle)
                            onRemoteControl?.invoke(steer, decoded.throttle)
                            maybeStartVideo(senderAddress, ::log)
                        }
                        is ControlFrame.Decoded.SteerLimit -> {
                            log("steer limit (speed-based): ${decoded.enabled}")
                            setSpeedLimitEnabled(decoded.enabled)
                            // Always keep the firmware's own throttle-position-based
                            // limiter off - it would double-limit alongside this one.
                            activeLink?.setSteerLimitEnabled(false)
                        }
                        is ControlFrame.Decoded.Quality -> {
                            val quality = VideoQuality.fromIndex(decoded.presetIndex)
                            log("quality: ${quality.label}")
                            videoSender.setQuality(quality)
                            applyAudioForQuality(quality, ::log)
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
                onListeningStateChanged?.invoke(true)
            } catch (e: Exception) {
                log("listen failed: ${e::class.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * Watches for the underlying OS network churning (a VPN app swap, or our
     * own embedded Yggdrasil's wifi-triggered internal reconnect) and refreshes
     * the video/audio/control sockets when it does - see the two detection
     * signals' own docs (handle vs. lastConnectAtMs) for why both are needed.
     * Runs on serviceScope, not an Activity's lifecycleScope - the whole reason
     * this moved here, see class doc.
     */
    private fun startNetworkChurnWatch() {
        var lastNetworkHandle: Long? = null
        var hasPolledNetworkHandleOnce = false
        var lastConnectAtMs = 0L
        var hasPolledConnectAtOnce = false
        serviceScope.launch {
            while (isActive) {
                val handle = ExternalYggdrasilDetector.currentNetworkHandle(this@VehicleForegroundService)
                // Covers a VPN app swap (a genuinely different tun/Network
                // object taking over the OS VPN slot). Deliberately compares
                // against the last poll's value even when either side is null,
                // not just "both non-null and different" - confirmed live as a
                // real bug with the stricter check: a VPN churn almost always
                // passes through a brief moment with no active network at all,
                // and the *next* poll - the one that should catch the real
                // reconnect - saw "last was null" and silently skipped it.
                val handleChanged = hasPolledNetworkHandleOnce && handle != lastNetworkHandle
                lastNetworkHandle = handle
                hasPolledNetworkHandleOnce = true

                // Covers the case the handle above doesn't: our OWN embedded
                // Yggdrasil's wifi-triggered internal reconnect keeps the SAME
                // outer VpnService/tun/Network object the whole time - only its
                // *internal* peer connection drops and re-dials - so the handle
                // never changes for that case. YggdrasilVpnService bumps this
                // on every successful connectMesh() instead, whether that's a
                // brand new session or the same tun's peer link re-dialing.
                // Stays 0 while piggybacking on the standalone app, which is
                // fine - the handle check above is what covers that case.
                val connectedAt = YggdrasilController.lastConnectAtMs()
                val connectAtChanged = hasPolledConnectAtOnce && connectedAt != 0L && connectedAt != lastConnectAtMs
                lastConnectAtMs = connectedAt
                hasPolledConnectAtOnce = true

                if (handleChanged || connectAtChanged) {
                    log("network changed underneath the active session - refreshing video/audio/control sockets")
                    // Nothing above this catches a crash - confirmed live as a
                    // real, app-killing bug once already (a BindException race
                    // in ControlLink.restartListening(), since fixed at its
                    // source, but this is exactly the wrong place to ever let a
                    // second, different exception go uncaught again: it would
                    // take the entire service (and the whole app with it) down
                    // at precisely the moment it's recovering from a churn).
                    try {
                        videoSender.refreshSocket()
                        audioSender.refreshSocket()
                        // Also restarts the receive loop, not just the socket -
                        // see ControlLink.restartListening()'s doc for why a
                        // plain field swap doesn't reach it. Without this, the
                        // controller's own "connection lost" warning never
                        // clears even once video/audio have recovered, since
                        // the vehicle's Pong replies keep going out a dead
                        // socket the receive loop never lets go of.
                        controlLink.restartListening()
                    } catch (e: Exception) {
                        log("refreshing sockets after network change failed: ${e::class.simpleName}: ${e.message}")
                    }
                }
                delay(5000)
            }
        }
    }

    /**
     * Stops the camera/encoder and audio pipeline once no controller has sent a
     * Control frame in a while, and lets the next one arriving start it fresh
     * again - per direct request, battery: this phone typically stays mounted
     * and powered on far longer than anyone's actually driving it, and until
     * this existed, once ANY controller had ever connected, the camera/encoder
     * just kept running and transmitting forever, even to a controller that
     * closed its app, backgrounded without recording (ControllerDriveActivity's
     * own onPause() stops sending the instant that happens), or simply drove
     * off the mesh - there was no signal here that would ever have told this to
     * stop. lastRemoteControlAtMs is the same "is anyone actually driving"
     * signal the "Waiting for controller…"/"Controller signal lost" status text
     * already uses, just acted on instead of only displayed.
     *
     * Deliberately NOT the same loop/interval as startNetworkChurnWatch() -
     * different concern, different timing, no reason to entangle them.
     */
    private fun startControllerIdleWatch() {
        serviceScope.launch {
            while (isActive) {
                delay(5000)
                val lastControl = lastRemoteControlAtMs
                if (currentVideoControllerAddress != null &&
                    lastControl != 0L &&
                    System.currentTimeMillis() - lastControl > CONTROLLER_IDLE_STOP_MS
                ) {
                    log("no controller for a while - stopping video/audio to save battery")
                    try {
                        videoSender.stop()
                        audioSender.stop()
                    } catch (e: Exception) {
                        log("stopping video/audio for idle failed: ${e::class.simpleName}: ${e.message}")
                    }
                    // Not a retarget target anymore - the next Control frame,
                    // from this same controller or a new one, should go
                    // through maybeStartVideo()'s normal fresh-start path
                    // (now safe again - see VideoSender.cameraThread's doc),
                    // not be silently treated as "already streaming to them".
                    currentVideoControllerAddress = null
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // One of DebugFileLogger's several start() call sites - see its own doc
        // for why more than one exists (this one specifically covers Android
        // restarting just this START_STICKY service without MainActivity ever
        // running again first).
        serviceScope.launch {
            if (nu.bruijn.rcvehicle.mode.ModePrefs(applicationContext).debugFileLoggingEnabled.first()) {
                nu.bruijn.rcvehicle.common.DebugFileLogger.start(applicationContext)
            }
        }
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

        videoSender.onError = { msg -> log("video: $msg") }
        controllerVoiceReceiver.onError = { msg -> log("controller voice: $msg") }
        try {
            controllerVoiceReceiver.start(AudioPacket.CONTROLLER_TO_VEHICLE_PORT, pairingSecret)
        } catch (e: Exception) {
            log("controller voice listen failed: ${e::class.simpleName}: ${e.message}")
        }
        startListening()
        startNetworkChurnWatch()
        startControllerIdleWatch()

        // Was VehicleActivity-only (a dynamic receiver registered in its onCreate())
        // until this - confirmed on real hardware as a real gap: unplugging/replugging
        // the USB-OTG cable while that screen wasn't the foreground Activity (screen
        // locked/timed out, which this service is explicitly designed to allow - see
        // its own class doc - or the driver just on a different screen) did nothing at
        // all, silently leaving UsbServoBridge on whatever channel mapping (possibly
        // still the constructor's placeholder defaults, if the very first connect
        // attempt raced GETJOYCHANNELS) it last resolved. Living here instead means a
        // reconnect always re-queries the board's actual configured channels - see
        // ServoLink.readyToSend's doc - regardless of which screen, if any, is in
        // front.
        ContextCompat.registerReceiver(
            this,
            usbAttachReceiver,
            android.content.IntentFilter().apply {
                addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private val usbAttachReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED -> connectUsb()
                android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED -> usbServoBridge.stop()
            }
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

    /**
     * Per direct request: swiping this app away in the Recents/overview screen
     * should be a real, deliberate way to stop the vehicle session - not just
     * Force Stop buried in system Settings. Without this override, Android's
     * default for a startForegroundService()-started service (not just bound)
     * is to keep running regardless - which is what was happening before this
     * existed, confirmed live: nobody had ever explicitly decided that either
     * way, it was just the unaddressed default.
     *
     * Trade-off, stated plainly rather than silently traded away: this also
     * means an ACCIDENTAL swipe - someone else picks up the mounted phone, a
     * system-triggered recents cleanup - now stops the vehicle too, possibly
     * mid-drive. There's no way from here to tell "the user meant this" apart
     * from "this just happened".
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbAttachReceiver) }
        servoBridge.stop()
        usbServoBridge.stop()
        controlLink.close()
        videoSender.stop()
        audioSender.stop()
        controllerVoiceReceiver.stop()
        locationListener?.let { locationManager?.removeUpdates(it) }
        serviceScope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
}
