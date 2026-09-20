package nu.bruijn.rcvehicle.controller

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.input.InputManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.View
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nu.bruijn.rcvehicle.R
import nu.bruijn.rcvehicle.audio.AudioPacket
import nu.bruijn.rcvehicle.audio.AudioReceiver
import nu.bruijn.rcvehicle.audio.AudioSender
import nu.bruijn.rcvehicle.common.showPermissionDeniedIfAny
import nu.bruijn.rcvehicle.control.ControlFrame
import nu.bruijn.rcvehicle.control.ControlLink
import nu.bruijn.rcvehicle.mode.ModePrefs
import nu.bruijn.rcvehicle.video.MuxerSession
import nu.bruijn.rcvehicle.video.VideoPacket
import nu.bruijn.rcvehicle.video.VideoQuality
import nu.bruijn.rcvehicle.video.VideoReceiver

/**
 * The actual driving screen, landscape-locked (manifest) - split out from
 * ControllerActivity (now pairing/setup only) so the joystick/video UI never has
 * to share space with the portrait setup form, per direct request. Connects its
 * own ControlLink using the vehicle address passed in from setup, and renders
 * the video the vehicle sends back once it has learned this phone's address from
 * that same control traffic (see ControlLink's/VideoSender's docs for why these
 * are plain UDP sockets instead of the originally-planned WebRTC data channel/
 * video track).
 *
 * The video is always shown at its correct aspect ratio (never stretched) and
 * rotated to match the vehicle phone's own current orientation - see
 * updateVideoTransform()'s doc for how, and VideoSender/VideoPacket for how the
 * rotation value gets here in the first place.
 */
class ControllerDriveActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VEHICLE_ADDRESS = "vehicle_address"
        const val EXTRA_VEHICLE_SECRET = "vehicle_secret"

        // The decoder is configured for VideoQuality.HIGH's dimensions regardless of
        // which preset the vehicle is actually encoding at right now: decoders can
        // freely handle an incoming SPS declaring a resolution *smaller* than what
        // they were configured for, but going *above* it is what typically requires
        // a reconfigure - so configuring at the largest preset up front means the
        // resolution picker/auto-quality loop below never needs to touch the
        // decoder, only the encoder (VideoSender.setQuality()). All four presets
        // share a 4:3 aspect ratio for exactly this reason - the aspect-ratio math
        // in updateVideoTransform() below doesn't need to know which one is active.
        private val VIDEO_WIDTH = VideoQuality.HIGH.width
        private val VIDEO_HEIGHT = VideoQuality.HIGH.height

        // Auto-quality hysteresis: onFps fires ~once/sec, so these are seconds.
        // Stepping down fast but stepping back up slowly avoids flapping between
        // two presets right at the edge of what the link can sustain.
        private const val LOW_FPS_THRESHOLD = 12f
        private const val HEALTHY_FPS_THRESHOLD = 18f
        private const val STEP_DOWN_AFTER_TICKS = 3
        private const val STEP_UP_AFTER_TICKS = 6
    }

    private val controlLink = ControlLink()
    private val videoReceiver = VideoReceiver()
    // Muted from construction, not just "eventually" via the restore coroutine
    // below - confirmed on real hardware as a real bug otherwise: that
    // coroutine sets audioReceiver.muted = true only after several earlier
    // suspend calls (autoQuality/safeSteering/telemetry reads), and
    // onResume()'s startAudio()/startMic() run essentially immediately,
    // often before those finish - a real, audible window of unmuted audio
    // right at the start of every single connection, exactly what "always
    // muted until you tap it yourself" was supposed to prevent.
    private val audioReceiver = AudioReceiver().apply { muted = true }

    // Talk-back mic (two-way audio, per direct request) - VOICE_COMMUNICATION, same
    // reasoning as VehicleActivity's own AudioSender doc, gets echo cancellation/
    // noise suppression on hardware that supports it since this is a real-time
    // conversation, not an ambient feed. Unlike audioReceiver (started once for the
    // whole screen's lifetime), this only runs while not muted - see startMic().
    private val micSender = AudioSender(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
    private val tickHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null
    private var seq = 0
    private var currentRotation = 0

    // Latest joystick positions, read by the tick loop - not sent directly from
    // onMove itself, so the send rate stays a steady ~33Hz regardless of touch
    // event frequency (same reasoning as the vehicle-side debug sliders).
    private var steerX = 0f
    private var throttleY = 0f

    // Promoted to fields (rather than onCreate-locals) so the gamepad's LB button
    // (onKeyDown/onKeyUp below) can drive the exact same toggle the touch button
    // does - see setRearView()'s doc.
    private lateinit var buttonRearView: Button
    private var defaultRearViewTint: android.content.res.ColorStateList? = null
    private var rearViewActive = false

    // Same reasoning as buttonRearView above - set in setUpRecordButton(), used
    // from onKeyDown()'s gamepad B-button handling.
    private lateinit var buttonRecord: ImageButton

    // Same reasoning - set in onCreate, used from onKeyDown()'s gamepad
    // R1/RB handling.
    private lateinit var buttonMute: ImageButton

    // Same reasoning - set in onCreate, used from updateSliderVisibility() below.
    private lateinit var sliderSteer: SliderView
    private lateinit var sliderThrottle: SliderView

    // Debug overlay state.
    private var lastFps = -1f
    private var lastLatencyMs = -1L
    private var lastBandwidthBytesPerSec = -1L
    private var lastBandwidthSampleAtMs = 0L
    private var lastBandwidthSampleTotalBytes = 0L
    private var telemetryEnabled = false
    private var lastTelemetry: ControlFrame.Decoded.Telemetry? = null

    // Connectivity liveness (always visible, not debug - see textConnectionWarning's doc):
    // lastPongAtMs is 0 until the very first reply, so "never connected" and "was
    // connected, now silent" read differently. Yggdrasil's own app dropping its VPN when
    // wifi turns off, with no automatic mobile-data fallback on some phones, is exactly
    // the kind of silent failure this needs to catch.
    private var lastPongAtMs = 0L
    private var connectStartedAtMs = 0L

    // Set whenever the vehicle rejects a Control frame because another
    // controller already owns it (ControlLink.listen()'s ownership lock) -
    // self-expiring in the tick loop's warning check below rather than a
    // manually-cleared flag, same reasoning as lastPongAtMs: a Busy reply
    // that stops arriving (the other controller went away, or this one got
    // accepted) should stop being shown without any extra bookkeeping.
    private var lastBusyAtMs = 0L

    // Video quality: currentQualityIndex is what the vehicle is actually encoding
    // at (VideoQuality's ordinal); autoQuality toggles the FPS-driven step-down/
    // step-up loop in videoReceiver.onFps below. Manual button taps turn autoQuality
    // off, so a deliberate low-bandwidth choice never gets silently overridden.
    // LOW/false is the actual default (ModePrefs.autoQualityEnabled) for a fresh
    // install or a vehicle with no saved preference yet, not just this in-memory
    // starting point - see its doc.
    private var currentQualityIndex = VideoQuality.LOW.ordinal
    private var autoQuality = false
    private var lowFpsStreak = 0
    private var healthyFpsStreak = 0
    private var steerLimitEnabled = false

    // Promoted to fields (rather than onCreate-local vals) so onPause()/onResume()
    // can restart video/audio/mic and log to the same views - see their doc for why
    // that's needed (screen lock doesn't reliably re-fire onSurfaceTextureAvailable).
    private lateinit var logView: TextView
    private lateinit var textureVideoView: TextureView
    private lateinit var videoContainerView: FrameLayout
    private lateinit var modePrefs: ModePrefs
    private var vehicleAddress: String? = null
    private var vehicleSecret: ByteArray? = null

    private fun log(line: String) = runOnUiThread {
        Log.d("RCDebug", line)
        logView.append(line + "\n")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_controller_drive)

        // One of DebugFileLogger's several start() call sites - see its own doc.
        lifecycleScope.launch {
            if (ModePrefs(applicationContext).debugFileLoggingEnabled.first()) {
                nu.bruijn.rcvehicle.common.DebugFileLogger.start(applicationContext)
            }
        }

        // Full-bleed video behind the status/nav bars looks fine, but the debug
        // overlay text (top-right) and the log (top-left) don't - on a Pixel's
        // edge-to-edge default they render right under the status bar's clock/
        // battery icons. This is a driving screen anyway (video fills the whole
        // frame), so go fully immersive instead of just padding the overlays down -
        // a swipe from the edge still reveals the bars if needed.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        vehicleAddress = intent.getStringExtra(EXTRA_VEHICLE_ADDRESS)
        vehicleSecret = intent.getByteArrayExtra(EXTRA_VEHICLE_SECRET)
        val address = vehicleAddress
        modePrefs = ModePrefs(applicationContext)
        logView = findViewById(R.id.textRtcLog)
        videoContainerView = findViewById(R.id.videoContainer)
        textureVideoView = findViewById(R.id.textureVideo)
        val videoContainer = videoContainerView
        val textureVideo = textureVideoView
        val textDebugOverlay = findViewById<TextView>(R.id.textDebugOverlay)
        val textTelemetryOverlay = findViewById<TextView>(R.id.textTelemetryOverlay)
        val textConnectionWarning = findViewById<TextView>(R.id.textConnectionWarning)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        // Verbose log + FPS/latency numbers + the resolution picker + Safe steering
        // (toggle and curve both) are diagnostic/rarely-needed, hidden unless "Debug
        // mode" is on (MainActivity) - textConnectionWarning below stays visible
        // regardless, that one's a safety signal, not a diagnostic. Safe steering
        // moved back into debug per direct request - still persists/re-applies via
        // ModePrefs either way, so it keeps working even while its own toggle isn't shown.
        val qualitySection = findViewById<View>(R.id.qualitySection)
        val steerLimitConfigSection = findViewById<View>(R.id.steerLimitConfigSection)
        val buttonSteerLimitView = findViewById<View>(R.id.buttonSteerLimit)
        lifecycleScope.launch {
            val debugEnabled = modePrefs.debugEnabled.first()
            logView.visibility = if (debugEnabled) View.VISIBLE else View.GONE
            textDebugOverlay.visibility = if (debugEnabled) View.VISIBLE else View.GONE
            qualitySection.visibility = if (debugEnabled) View.VISIBLE else View.GONE
            steerLimitConfigSection.visibility = if (debugEnabled) View.VISIBLE else View.GONE
            buttonSteerLimitView.visibility = if (debugEnabled) View.VISIBLE else View.GONE
        }

        fun updateDebugOverlay() {
            val fpsText = if (lastFps >= 0) String.format("%.0f", lastFps) else "--"
            val latencyText = if (lastLatencyMs >= 0) lastLatencyMs.toString() else "--"
            val bwText = when {
                lastBandwidthBytesPerSec < 0 -> "--"
                lastBandwidthBytesPerSec >= 1_000_000 -> String.format("%.1f MB/s", lastBandwidthBytesPerSec / 1_000_000.0)
                else -> String.format("%.0f KB/s", lastBandwidthBytesPerSec / 1_000.0)
            }
            textDebugOverlay.text = "FPS: $fpsText | Latency: $latencyText ms | BW: $bwText"
        }

        // Total incoming bytes (video+audio+control combined - together, video's
        // the overwhelming majority, but per direct request this should actually
        // reflect what crossed the network, not just estimate off video alone) since
        // the connection started, sampled here rather than computed fresh each call -
        // called from videoReceiver.onFps below, already firing ~once/sec, no reason
        // for a second timer just for this.
        fun sampleBandwidth() {
            val now = System.currentTimeMillis()
            val totalBytes = videoReceiver.totalBytesReceived + audioReceiver.totalBytesReceived + controlLink.totalBytesReceived
            if (lastBandwidthSampleAtMs != 0L) {
                val elapsedMs = now - lastBandwidthSampleAtMs
                if (elapsedMs > 0) {
                    lastBandwidthBytesPerSec = (totalBytes - lastBandwidthSampleTotalBytes) * 1000 / elapsedMs
                }
            }
            lastBandwidthSampleAtMs = now
            lastBandwidthSampleTotalBytes = totalBytes
        }

        // GPS/speed HUD on the video itself - its own overlay (per direct request,
        // distinct from the small debug text above), visible whenever the debug-only
        // checkTelemetry checkbox is on, regardless of general debug mode. Live only -
        // recordings stay a direct remux with no per-frame drawing (real CPU/battery/
        // quality cost that wasn't worth it, per direct decision).
        fun updateTelemetryOverlay() {
            if (!telemetryEnabled) {
                textTelemetryOverlay.visibility = View.GONE
                return
            }
            textTelemetryOverlay.visibility = View.VISIBLE
            val t = lastTelemetry
            textTelemetryOverlay.text = if (t == null || !t.hasFix) {
                "GPS: no fix"
            } else {
                // Coordinates deliberately left off this on-screen HUD (a phone
                // aimed at bystanders while driving shouldn't broadcast exact
                // position) - still written in full to the .gpx track file
                // (GpxTrack.addPoint() above), just not shown live.
                "${String.format("%.1f", t.speedKmh)} km/h"
            }
        }

        fun setQuality(index: Int) {
            val clamped = index.coerceIn(0, VideoQuality.entries.size - 1)
            if (clamped == currentQualityIndex) return
            currentQualityIndex = clamped
            controlLink.sendQuality(clamped)
            // Per direct request - persisted regardless of whether this came from a
            // manual button tap or the auto-quality FPS loop stepping itself, so
            // next session starts at whatever quality was actually last in use.
            lifecycleScope.launch { modePrefs.setLastVideoQuality(clamped) }
        }

        videoReceiver.onError = { msg -> log("video: $msg") }
        audioReceiver.onError = { msg -> log("audio: $msg") }
        videoReceiver.onRotationChanged = { rotation ->
            runOnUiThread {
                currentRotation = rotation
                updateVideoTransform(textureVideo, videoContainer)
            }
        }
        videoReceiver.onFps = { fps ->
            runOnUiThread {
                lastFps = fps
                sampleBandwidth()
                updateDebugOverlay()
                if (autoQuality) {
                    when {
                        fps < LOW_FPS_THRESHOLD -> {
                            lowFpsStreak++
                            healthyFpsStreak = 0
                            if (lowFpsStreak >= STEP_DOWN_AFTER_TICKS) {
                                // Never auto-steps down into Ultra Low - see its
                                // own doc: it targets ~15fps by design, which
                                // would keep this loop permanently below
                                // HEALTHY_FPS_THRESHOLD and unable to ever step
                                // back up on its own. Reachable only by tapping
                                // its own button.
                                setQuality(maxOf(currentQualityIndex - 1, VideoQuality.LOW.ordinal))
                                lowFpsStreak = 0
                            }
                        }
                        fps > HEALTHY_FPS_THRESHOLD -> {
                            healthyFpsStreak++
                            lowFpsStreak = 0
                            if (healthyFpsStreak >= STEP_UP_AFTER_TICKS) {
                                setQuality(currentQualityIndex + 1)
                                healthyFpsStreak = 0
                            }
                        }
                        else -> {
                            lowFpsStreak = 0
                            healthyFpsStreak = 0
                        }
                    }
                }
            }
        }

        // Start listening for video as soon as the surface exists - independent of
        // the control-link connect below, since it's the vehicle that learns OUR
        // address (from control traffic) and initiates the video stream.
        textureVideo.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                updateVideoTransform(textureVideo, videoContainer)
                // A recording in progress kept the receiver alive through a
                // background/foreground cycle (see onPause()'s doc) - just
                // reattach it to this (possibly brand new) Surface instead of
                // tearing down and reopening the socket, which would
                // interrupt the very recording this exists to protect.
                if (videoReceiver.isRunning) {
                    videoReceiver.updateOutputSurface(Surface(surface))
                } else {
                    startVideo(surface)
                }
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                updateVideoTransform(textureVideo, videoContainer)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                // Not while recording - see onPause()'s doc, same reasoning:
                // the receive loop (and the recording riding on it) must
                // survive this, only the display surface is going away.
                if (!isRecording) videoReceiver.stop()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        val secret = vehicleSecret
        if (address.isNullOrEmpty()) {
            log("no vehicle address given")
        } else if (secret == null) {
            // ControllerActivity.connect() always resolves+passes a secret before
            // starting this Activity - reaching here means something started it
            // directly instead, which shouldn't happen outside of that flow.
            log("no pairing secret given - can't authenticate control traffic")
        } else {
            connectStartedAtMs = System.currentTimeMillis()
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    controlLink.connect(address, ControlLink.PORT, secret)
                    log("UDP control link connected to $address:${ControlLink.PORT}")
                    controlLink.startPing(
                        onRtt = { rtt ->
                            runOnUiThread {
                                lastLatencyMs = rtt
                                lastPongAtMs = System.currentTimeMillis()
                                updateDebugOverlay()
                            }
                        },
                        onTelemetry = { telemetry ->
                            runOnUiThread {
                                lastTelemetry = telemetry
                                updateTelemetryOverlay()
                                if (isRecording) gpxTrack?.addPoint(telemetry)
                            }
                        },
                        onBusy = {
                            runOnUiThread { lastBusyAtMs = System.currentTimeMillis() }
                        },
                    )

                    // Re-apply Safe steering/GPS-speed telemetry to whichever vehicle
                    // this is, since a fresh vehicle doesn't otherwise know either
                    // was on last session - see ModePrefs.safeSteeringEnabled's doc.
                    if (modePrefs.safeSteeringEnabled.first()) {
                        controlLink.sendSteerLimit(true)
                        controlLink.sendSteerLimitConfig(
                            modePrefs.safeSteeringMaxSpeedKmh.first().toFloat(),
                            modePrefs.safeSteeringReductionPercent.first() / 100f,
                        )
                    }
                    if (modePrefs.telemetryEnabled.first()) {
                        controlLink.sendTelemetryEnable(true)
                    }

                    // Restored here (not just in the UI-restore coroutine below) for
                    // the same reason safeSteeringEnabled/telemetryEnabled are also
                    // read directly in this block - this coroutine and that one run
                    // independently, so relying on the other having already finished
                    // isn't safe. Per direct request: whatever quality was last
                    // actually in use (manually picked, or auto-quality's last
                    // settled-on step) is where a fresh connection starts, not
                    // always Low.
                    currentQualityIndex = modePrefs.lastVideoQuality.first().coerceIn(0, VideoQuality.entries.size - 1)
                    // Button tints reflect this too - see the UI-restore coroutine
                    // below (qualityButtons/updateQualityButtonTints aren't in scope
                    // here yet, being declared further down this same onCreate).

                    // Confirmed on real hardware as a real bug: a fresh vehicle
                    // always starts its own encoder at VideoQuality.HIGH
                    // (VehicleActivity.videoSender.start()) regardless of what
                    // this screen's quality buttons show - and setQuality()
                    // below is a no-op whenever the requested preset already
                    // equals currentQualityIndex, which it always does on a
                    // brand-new connection (nothing changed it yet). Without
                    // this, the UI could show Low tinted green while the
                    // vehicle kept streaming at High until a button tap
                    // actually changed currentQualityIndex to something else
                    // and back. Send unconditionally so the vehicle always
                    // actually matches what's shown, not just when it changes.
                    controlLink.sendQuality(currentQualityIndex)
                } catch (e: Exception) {
                    log("connect failed: ${e::class.simpleName}: ${e.message}")
                }
            }
        }

        val activeTint = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2E7D32"))

        val buttonSteerLimit = findViewById<Button>(R.id.buttonSteerLimit)
        val defaultSteerLimitTint = buttonSteerLimit.backgroundTintList
        buttonSteerLimit.setOnClickListener {
            steerLimitEnabled = !steerLimitEnabled
            controlLink.sendSteerLimit(steerLimitEnabled)
            buttonSteerLimit.backgroundTintList = if (steerLimitEnabled) activeTint else defaultSteerLimitTint
            lifecycleScope.launch { modePrefs.setSafeSteeringEnabled(steerLimitEnabled) }
        }

        val textSteerLimitMaxSpeed = findViewById<TextView>(R.id.textSteerLimitMaxSpeed)
        val textSteerLimitReduction = findViewById<TextView>(R.id.textSteerLimitReduction)
        val seekSteerLimitMaxSpeed = findViewById<SeekBar>(R.id.seekSteerLimitMaxSpeed)
        val seekSteerLimitReduction = findViewById<SeekBar>(R.id.seekSteerLimitReduction)

        // Matches VehicleActivity's own DEFAULT_MAX_SPEED_FOR_FULL_STEER_LIMIT_KMH/
        // DEFAULT_MAX_STEER_REDUCTION_FRACTION - both sides start in agreement, so
        // Safe steering works sensibly even if these sliders are never touched.
        // Overwritten below once the persisted values (if any) have loaded.
        seekSteerLimitMaxSpeed.progress = 10
        seekSteerLimitReduction.progress = 50
        textSteerLimitMaxSpeed.text = getString(R.string.controller_steer_limit_max_speed, seekSteerLimitMaxSpeed.progress)
        textSteerLimitReduction.text = getString(R.string.controller_steer_limit_reduction, seekSteerLimitReduction.progress)

        fun sendSteerLimitConfig() {
            val maxSpeed = seekSteerLimitMaxSpeed.progress
            val reduction = seekSteerLimitReduction.progress
            controlLink.sendSteerLimitConfig(maxSpeed.toFloat(), reduction / 100f)
            lifecycleScope.launch { modePrefs.setSafeSteeringConfig(maxSpeed, reduction) }
        }
        seekSteerLimitMaxSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                textSteerLimitMaxSpeed.text = getString(R.string.controller_steer_limit_max_speed, progress)
                if (fromUser) sendSteerLimitConfig()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        seekSteerLimitReduction.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                textSteerLimitReduction.text = getString(R.string.controller_steer_limit_reduction, progress)
                if (fromUser) sendSteerLimitConfig()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Four equal buttons, exactly one tinted green at a time - Low/Med/High
        // pick a fixed preset and turn Auto off; Auto hands quality back to the
        // FPS-driven step loop (videoReceiver.onFps above). Replaces an earlier
        // three-buttons-plus-checkbox layout per direct request: same four
        // mutually exclusive choices, but every affordance now looks and behaves
        // the same way.
        val buttonQualityUltraLow = findViewById<Button>(R.id.buttonQualityUltraLow)
        val buttonQualityLow = findViewById<Button>(R.id.buttonQualityLow)
        val buttonQualityMedium = findViewById<Button>(R.id.buttonQualityMedium)
        val buttonQualityHigh = findViewById<Button>(R.id.buttonQualityHigh)
        val buttonQualityAuto = findViewById<Button>(R.id.buttonQualityAuto)
        val defaultQualityTint = buttonQualityLow.backgroundTintList
        val qualityButtons = listOf(
            VideoQuality.ULOW.ordinal to buttonQualityUltraLow,
            VideoQuality.LOW.ordinal to buttonQualityLow,
            VideoQuality.MEDIUM.ordinal to buttonQualityMedium,
            VideoQuality.HIGH.ordinal to buttonQualityHigh,
        )

        fun updateQualityButtonTints() {
            qualityButtons.forEach { (index, button) ->
                button.backgroundTintList = if (!autoQuality && index == currentQualityIndex) activeTint else defaultQualityTint
            }
            buttonQualityAuto.backgroundTintList = if (autoQuality) activeTint else defaultQualityTint
        }

        qualityButtons.forEach { (index, button) ->
            button.setOnClickListener {
                autoQuality = false
                setQuality(index)
                updateQualityButtonTints()
                lifecycleScope.launch { modePrefs.setAutoQualityEnabled(false) }
            }
        }
        buttonQualityAuto.setOnClickListener {
            autoQuality = true
            updateQualityButtonTints()
            lifecycleScope.launch { modePrefs.setAutoQualityEnabled(true) }
        }
        // So LOW is visibly tinted right away rather than only once the restore
        // coroutine below finishes its first async read.
        updateQualityButtonTints()

        val checkTelemetry = findViewById<CheckBox>(R.id.checkTelemetry)
        checkTelemetry.setOnCheckedChangeListener { _, checked ->
            telemetryEnabled = checked
            controlLink.sendTelemetryEnable(checked)
            if (!checked) lastTelemetry = null
            updateTelemetryOverlay()
            lifecycleScope.launch { modePrefs.setTelemetryEnabled(checked) }
        }

        buttonRearView = findViewById(R.id.buttonRearView)
        defaultRearViewTint = buttonRearView.backgroundTintList
        buttonRearView.setOnClickListener { setRearView(!rearViewActive) }

        // One toggle for both directions of the mic link, per direct request: muting
        // stops listening to the vehicle's mic (audioReceiver.muted, as before) AND
        // stops transmitting this phone's own mic to the vehicle (startMic()/
        // micSender.stop()) - not two separate controls. audioReceiver.muted is the
        // single source of truth for "muted" (no separate flag to keep in sync).
        // Icon (speaker / speaker-with-slash) communicates state, so no tint needed.
        buttonMute = findViewById(R.id.buttonMute)
        fun applyMuteUi() {
            buttonMute.setImageResource(
                if (audioReceiver.muted) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_lock_silent_mode_off,
            )
        }
        buttonMute.setOnClickListener {
            audioReceiver.muted = !audioReceiver.muted
            applyMuteUi()
            if (audioReceiver.muted) micSender.stop() else startMic()
        }

        // Restores Auto quality/Safe steering/Mute/GPS-speed overlay from last
        // session - per direct request ("onthou..."). Runs after the views/listeners
        // above exist, so setting .isChecked/.progress here naturally drives their
        // existing update logic (label text, tint) instead of duplicating it. Safe
        // steering's and telemetry's own ControlFrame messages are re-sent once
        // actually connected (see the controlLink.connect() block above) - this part
        // only restores local UI state and audioReceiver.muted/micSender, which
        // don't need the vehicle to know anything.
        lifecycleScope.launch {
            autoQuality = modePrefs.autoQualityEnabled.first()
            currentQualityIndex = modePrefs.lastVideoQuality.first().coerceIn(0, VideoQuality.entries.size - 1)
            updateQualityButtonTints()

            steerLimitEnabled = modePrefs.safeSteeringEnabled.first()
            buttonSteerLimit.backgroundTintList = if (steerLimitEnabled) activeTint else defaultSteerLimitTint

            seekSteerLimitMaxSpeed.progress = modePrefs.safeSteeringMaxSpeedKmh.first()
            seekSteerLimitReduction.progress = modePrefs.safeSteeringReductionPercent.first()

            telemetryEnabled = modePrefs.telemetryEnabled.first()
            checkTelemetry.isChecked = telemetryEnabled

            // Already muted from construction (see the field's own doc) - just
            // sync the UI icon to match here.
            applyMuteUi()
            if (!audioReceiver.muted) startMic() else micSender.stop()
        }

        setUpRecordButton(::log)

        sliderSteer = findViewById<SliderView>(R.id.sliderSteer).apply {
            vertical = false
            onMove = { x -> steerX = x }
        }
        // Touch y increases downward; up (forward throttle) must be positive, so negate.
        sliderThrottle = findViewById<SliderView>(R.id.sliderThrottle).apply {
            vertical = true
            onMove = { y -> throttleY = -y }
        }
        updateSliderVisibility()
        inputManager.registerInputDeviceListener(inputDeviceListener, null)

        var lastWarningText: String? = null

        // Fixed ~33Hz tick, same reasoning as the vehicle-side debug sliders: a
        // steady heartbeat is what the vehicle-side failsafe is designed to key off.
        tickRunnable = object : Runnable {
            override fun run() {
                controlLink.send(steerX, throttleY, seq++)

                if (!address.isNullOrEmpty()) {
                    val now = System.currentTimeMillis()
                    val warning = when {
                        // Checked first - a stale-but-still-arriving Busy reply means
                        // there IS a live link to the vehicle, just not one this
                        // controller is allowed to drive on, which is a more useful
                        // thing to tell the user than "no connection".
                        now - lastBusyAtMs < 3000 -> "⚠ Vehicle already connected to another controller"
                        lastPongAtMs == 0L && now - connectStartedAtMs > 5000 -> "⚠ No connection to vehicle"
                        lastPongAtMs != 0L && now - lastPongAtMs > 2000 -> "⚠ Vehicle connection lost (${(now - lastPongAtMs) / 1000}s)"
                        else -> null
                    }
                    if (warning != lastWarningText) {
                        lastWarningText = warning
                        textConnectionWarning.text = warning ?: ""
                        textConnectionWarning.visibility = if (warning != null) View.VISIBLE else View.GONE
                    }
                }

                tickHandler.postDelayed(this, 30)
            }
        }
        // Not started here - onResume() does it, so the same single start point
        // covers both first launch (onResume always follows onCreate) and coming
        // back from a pause/lock. See onPause()'s doc for why this matters.
    }

    private fun startVideo(surface: SurfaceTexture) {
        val secret = vehicleSecret ?: run {
            log("no pairing secret - can't authenticate video")
            return
        }
        videoReceiver.stop() // idempotent - safe even if it wasn't already running
        try {
            videoReceiver.start(VideoPacket.PORT, Surface(surface), secret, VIDEO_WIDTH, VIDEO_HEIGHT)
            log("listening for video on port ${VideoPacket.PORT}")
        } catch (e: Exception) {
            log("video listen failed: ${e::class.simpleName}: ${e.message}")
        }
    }

    private fun startAudio() {
        val secret = vehicleSecret ?: run {
            log("no pairing secret - can't authenticate audio")
            return
        }
        audioReceiver.stop()
        try {
            audioReceiver.start(AudioPacket.VEHICLE_TO_CONTROLLER_PORT, secret)
            log("listening for audio on port ${AudioPacket.VEHICLE_TO_CONTROLLER_PORT}")
        } catch (e: Exception) {
            log("audio listen failed: ${e::class.simpleName}: ${e.message}")
        }
    }

    /** Starts capturing+sending this phone's own mic to the vehicle - a no-op while
     * muted (audioReceiver.muted doubles as the mic-mute flag, see the Mute button's
     * doc) or before a vehicle address is known. Safe to call repeatedly/from
     * multiple places (onResume, the Mute button, the initial settings load, which
     * can all race) - always stops any previous capture first. */
    private fun startMic() {
        if (audioReceiver.muted) return
        val addr = vehicleAddress
        if (addr.isNullOrEmpty()) return
        val secret = vehicleSecret ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        micSender.stop()
        try {
            micSender.start(addr, AudioPacket.CONTROLLER_TO_VEHICLE_PORT, secret)
        } catch (e: Exception) {
            log("mic start failed: ${e::class.simpleName}: ${e.message}")
        }
    }

    /**
     * Locking the phone doesn't reliably tear down and recreate the TextureView's
     * Surface on every device - confirmed by direct report: video never came back
     * after unlocking, only a full app restart fixed it (onSurfaceTextureAvailable
     * simply never fired again). Rather than depend on that pairing, stop
     * everything explicitly here and restart it explicitly in onResume() -
     * EXCEPT a recording in progress, per direct request: backgrounding shouldn't
     * cut a recording short just because nobody's looking at the screen right now.
     * videoReceiver/audioReceiver's receive loops are what recording actually
     * depends on (VideoRecorder remuxes the same access units fed to the decoder,
     * not anything read back off the Surface - see VideoReceiver's doc), and both
     * happily keep running with no visible Surface attached - decode calls into a
     * torn-down one just fail silently (caught, logged, non-fatal) without
     * affecting the parallel recorder?.feed() call. Video/audio can't restart
     * blind into whatever they were doing before an unknown pause length, but
     * recording's whole point is to keep going regardless.
     *
     * Stopping the steering/throttle tick loop here matters as much as the video:
     * without it, the loop kept sending whatever steerX/throttleY a finger left on
     * a slider at the moment of locking, continuously, for as long as the phone
     * stayed locked - the vehicle's own failsafe (falls back to neutral after a
     * gap in control frames) never got a gap to trigger on. That stale, frozen
     * command - not the restart itself - is the more likely explanation for the
     * servo "jittering" once a restart finally reconnected things. Same reasoning
     * for always stopping micSender regardless of recording - talk-back audio
     * goes TO the vehicle, isn't part of what's recorded (that's audioReceiver,
     * FROM the vehicle), and there's no reason to keep transmitting this phone's
     * own mic while nobody's holding it up to talk.
     */
    override fun onPause() {
        super.onPause()
        tickRunnable?.let { tickHandler.removeCallbacks(it) }
        micSender.stop()
        if (!isRecording) {
            // Nothing to preserve - tear all the way down to save battery
            // while backgrounded, same as always. (If recording, there IS
            // something to preserve, and these two are exactly what it
            // depends on - see this function's own doc.)
            videoReceiver.stop()
            audioReceiver.stop()
        }
    }

    override fun onResume() {
        super.onResume()
        tickRunnable?.let { tickHandler.post(it) }
        startMic()
        // If a recording kept video/audio alive through the background (see
        // onPause()'s doc), they're already running - restarting either would
        // reopen its socket and interrupt the very recording this exists to
        // protect. Video still needs its *display* surface reattached (the
        // TextureView's own Surface can be destroyed/recreated independently
        // of any of this - AudioTrack playback has no equivalent, nothing to
        // do there beyond not restarting it).
        if (!audioReceiver.isRunning) startAudio()
        val surface = textureVideoView.surfaceTexture
        if (videoReceiver.isRunning) {
            surface?.let { videoReceiver.updateOutputSurface(Surface(it)) }
        } else {
            surface?.let { startVideo(it) }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        showPermissionDeniedIfAny(grantResults)
    }

    private val inputManager by lazy { getSystemService(Context.INPUT_SERVICE) as InputManager }
    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = updateSliderVisibility()
        override fun onInputDeviceRemoved(deviceId: Int) = updateSliderVisibility()
        override fun onInputDeviceChanged(deviceId: Int) = updateSliderVisibility()
    }

    private fun isGamepadConnected(): Boolean =
        inputManager.inputDeviceIds.any { id ->
            val device = InputDevice.getDevice(id)
            device != null && !device.isVirtual && (device.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        }

    /** Per direct request: the touch sliders and a physical gamepad both drive
     * steerX/throttleY (see onGenericMotionEvent's doc) and would otherwise sit
     * on screen fighting for thumb space with the gamepad in hand - hidden
     * whenever one's connected, back the instant it's unplugged. Re-checked on
     * every add/remove/change (not just once) since a gamepad can be plugged
     * in or pulled at any point during a drive, not only at connect time. */
    private fun updateSliderVisibility() {
        val visibility = if (isGamepadConnected()) View.GONE else View.VISIBLE
        sliderSteer.visibility = visibility
        sliderThrottle.visibility = visibility
    }

    /**
     * Gamepad support (Logitech F310 over USB, per direct request) - no separate
     * "connect" step needed: Android already routes a joystick-sourced
     * MotionEvent to whichever window has focus the moment a stick moves, so
     * plugging it in and touching a stick is all it takes. Split left/right per
     * direct request, matching the touch UI's own separate steer/throttle
     * sliders rather than one combined stick: left stick X = steerX, right
     * stick Y = throttleY. AXIS_Z/AXIS_RZ is the right stick's standard Android
     * mapping in the F310's default XInput mode (its DirectInput mode reports
     * AXIS_RX/AXIS_RY instead - not handled here, switch the controller to X
     * mode on its back if it stops responding). Touch sliders keep working
     * untouched alongside this; whichever input moved most recently simply
     * wins until the other one moves.
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_MOVE && event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            steerX = applyStickDeadzone(event.getAxisValue(MotionEvent.AXIS_X))
            // Same up-is-positive convention as sliderThrottle's own onMove - stick
            // Y reports down as positive, same as touch, so negate here too.
            throttleY = applyStickDeadzone(-event.getAxisValue(MotionEvent.AXIS_RZ))
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // Small rest-position drift on a physical stick would otherwise creep into a
    // permanent tiny steer/throttle command instead of a clean 0 at center.
    private fun applyStickDeadzone(value: Float, deadzone: Float = 0.1f): Float =
        if (kotlin.math.abs(value) < deadzone) 0f else value

    /** Shared by the touch button (a persistent toggle) and the gamepad's LB
     * button (momentary - see onKeyDown/onKeyUp below), per direct request. */
    private fun setRearView(active: Boolean) {
        rearViewActive = active
        controlLink.sendCamera(rearViewActive)
        buttonRearView.text = getString(if (rearViewActive) R.string.controller_front_view else R.string.controller_rear_view)
        buttonRearView.backgroundTintList = if (rearViewActive) activeTintColor else defaultRearViewTint
    }

    private val activeTintColor by lazy { android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2E7D32")) }

    // Gamepad LB = "look back", held - per direct request, popular racing-game
    // convention (a shoulder button, momentary, not a toggle): rear camera while
    // held, back to front the instant it's released. repeatCount == 0 so a held
    // key's repeat events don't keep re-triggering setRearView(true) pointlessly.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L1 && event.repeatCount == 0) {
            setRearView(true)
            return true
        }
        // B (red on an Xbox-style pad, which is what the F310 in XInput mode
        // presents as) toggles recording, per direct request - reuses
        // buttonRecord's own click listener (performClick(), not calling
        // setUpRecordButton's logic directly) so this stays a single source of
        // truth for what "the record button" actually does.
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B && event.repeatCount == 0) {
            buttonRecord.performClick()
            return true
        }
        // RB toggles mute, per direct request - same performClick() reasoning
        // as B/record above.
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R1 && event.repeatCount == 0) {
            buttonMute.performClick()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L1) {
            setRearView(false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private var isRecording = false
    private var recordingDisplayPath: String? = null
    private var recordingUri: android.net.Uri? = null
    private var recordingPfd: android.os.ParcelFileDescriptor? = null
    private var muxerSession: MuxerSession? = null
    private var gpxTrack: GpxTrack? = null
    private var gpxBaseName: String? = null

    // Per direct request: a .gpx track is always captured while (and only
    // while) recording video, independent of the debug GPS/speed overlay
    // checkbox (telemetryEnabled) - that checkbox only controls whether the
    // overlay is shown, not whether the vehicle sends telemetry at all. If
    // recording starts while that checkbox happens to be off, this forces
    // telemetry on for the duration of the recording and back off again
    // when it stops - unless the user explicitly turned the checkbox on
    // themselves in the meantime, which takes priority.
    private var telemetryForcedForRecording = false

    /** Holds everything one recording attempt needs, whichever way it was created. */
    private class RecordingTarget(
        val session: MuxerSession,
        val uri: android.net.Uri?,
        val pfd: android.os.ParcelFileDescriptor?,
        val displayPath: String,
        val baseName: String,
    )

    /**
     * On API 29+, inserts a new entry into MediaStore's video collection (Movies/
     * MeshDrive/) and hands VideoRecorder a MediaMuxer built on that entry's own
     * FileDescriptor - the standard scoped-storage way to produce a file that shows
     * up in a gallery app immediately, no storage permission needed. IS_PENDING=1
     * hides it from other apps until setUpRecordButton() clears it on stop() (a
     * recording that's still being written isn't done yet). Below API 29 (scoped
     * storage didn't exist), falls back to this app's own external files dir -
     * works file, just isn't gallery-visible, but that combination is vanishingly
     * rare on real hardware at this point.
     */
    private fun createRecordingTarget(): RecordingTarget? {
        val baseName = "meshdrive_" +
            java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val fileName = "$baseName.mp4"

        // Whatever GPS fix is on hand right when recording starts (or none, if the
        // GPS/speed overlay was never turned on or hasn't got a fix yet) - see
        // MuxerSession's [location] doc for why this is a one-shot geotag rather
        // than anything that could track the vehicle's route through the file.
        val location = lastTelemetry?.takeIf { it.hasFix }?.let { it.lat to it.lon }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_MOVIES + "/MeshDrive")
                put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            val pfd = try {
                contentResolver.openFileDescriptor(uri, "rw")
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                return null
            } ?: run {
                contentResolver.delete(uri, null, null)
                return null
            }
            val muxer = android.media.MediaMuxer(pfd.fileDescriptor, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            return RecordingTarget(MuxerSession(muxer, currentRotation, location), uri, pfd, "Movies/MeshDrive/$fileName", baseName)
        }

        val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: return null
        dir.mkdirs()
        val file = java.io.File(dir, fileName)
        val muxer = android.media.MediaMuxer(file.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        return RecordingTarget(MuxerSession(muxer, currentRotation, location), null, null, file.absolutePath, baseName)
    }

    /**
     * Writes [track] as a .gpx sidecar next to the recording it belongs to
     * (RELATIVE_PATH matches createRecordingTarget()'s video exactly) - see
     * GpxTrack's doc. No-op if nothing was ever added to it (telemetry never
     * flowed during this recording - most commonly because the GPS/speed overlay
     * checkbox was off).
     */
    private fun saveGpxTrack(track: GpxTrack, baseName: String, log: (String) -> Unit) {
        if (track.isEmpty) return
        val bytes = track.toGpxXml().toByteArray(Charsets.UTF_8)
        val fileName = "$baseName.gpx"
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.Files.FileColumns.MIME_TYPE, "application/gpx+xml")
                    put(android.provider.MediaStore.Files.FileColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_MOVIES + "/MeshDrive")
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Files.getContentUri("external"), values) ?: return
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            } else {
                val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: return
                java.io.File(dir, fileName).writeBytes(bytes)
            }
            log("gpx track saved: $fileName")
        } catch (e: Exception) {
            log("gpx save failed: ${e.message}")
        }
    }

    /**
     * Records the incoming video (and, if it's actually flowing - see MuxerSession's
     * doc - audio) stream to an .mp4. VideoRecorder/AudioRecorderTrack remux the same
     * access units already being decoded, no re-encoding; see createRecordingTarget()
     * for where the file actually goes.
     */
    private fun setUpRecordButton(log: (String) -> Unit) {
        buttonRecord = findViewById(R.id.buttonRecord)
        buttonRecord.setOnClickListener {
            if (!isRecording) {
                val target = createRecordingTarget()
                if (target == null) {
                    log("record: could not create output file")
                    return@setOnClickListener
                }
                target.session.expectAudio = audioReceiver.hasReceivedAudio
                muxerSession = target.session
                videoReceiver.startRecording(target.session) { msg -> log("record: $msg") }
                audioReceiver.startRecording(target.session)
                recordingUri = target.uri
                recordingPfd = target.pfd
                recordingDisplayPath = target.displayPath
                gpxTrack = GpxTrack(target.baseName)
                gpxBaseName = target.baseName
                isRecording = true
                if (!telemetryEnabled) {
                    telemetryForcedForRecording = true
                    controlLink.sendTelemetryEnable(true)
                }
                buttonRecord.setImageResource(R.drawable.record_button_active)
                log("recording to ${target.displayPath}${if (target.session.expectAudio) " (with audio)" else ""}")
            } else {
                stopRecording(log)
            }
        }
    }

    /**
     * Finalizes whatever's currently recording, if anything - shared by the Record
     * button's own stop path, onPause() (so locking the phone mid-recording finishes
     * the file properly instead of leaving a stuck-pending, orphaned MuxerSession
     * that video/audio no longer feed once they're stopped for the pause), and
     * onDestroy().
     */
    private fun stopRecording(log: (String) -> Unit) {
        if (!isRecording) return
        videoReceiver.stopRecording()
        audioReceiver.stopRecording()
        val wroteAnything = muxerSession?.stop() ?: false
        muxerSession = null
        try {
            recordingPfd?.close()
        } catch (_: Exception) {
        }
        recordingPfd = null
        recordingUri?.let { uri ->
            if (wroteAnything) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    contentResolver.update(uri, values, null, null)
                }
            } else {
                // Nothing was ever written (no video arrived) - don't leave a
                // permanently-pending, empty entry behind in the gallery.
                contentResolver.delete(uri, null, null)
            }
        }
        recordingUri = null
        isRecording = false
        if (telemetryForcedForRecording) {
            telemetryForcedForRecording = false
            // Only actually turn it back off if the user didn't separately
            // switch the checkbox on themselves while recording was running -
            // that explicit choice takes priority over undoing our own force.
            if (!telemetryEnabled) controlLink.sendTelemetryEnable(false)
        }
        gpxTrack?.let { track -> gpxBaseName?.let { saveGpxTrack(track, it, log) } }
        gpxTrack = null
        gpxBaseName = null
        buttonRecord.setImageResource(R.drawable.record_button_idle)
        log(if (wroteAnything) "recording saved: $recordingDisplayPath" else "recording discarded - no video was received")
    }

    /**
     * Resizes textureVideo to the largest box that (a) fits inside its parent
     * container and (b) has the video's correct aspect ratio for the current
     * rotation (640x480 landscape-shaped at 0/180, swapped to 480x640
     * portrait-shaped at 90/270 - the vehicle's device orientation, not a fixed
     * camera-sensor assumption), then rotates its content to match.
     *
     * Two separate things have to agree for a rotated video to look right without
     * stretching: the VIEW's own footprint must already be the correctly-rotated
     * shape (step 1), and the Matrix must map the decoder's always-640x480 buffer
     * onto that footprint accounting for the rotation (step 2) - rotating a view
     * that's still stretched to the wrong (unrotated) shape just reorients the
     * distortion instead of fixing it. Step 2's swapped-rect-to-rect mapping is
     * the same technique Android's own Camera2 TextureView samples use for
     * rotating a preview without stretching it.
     */
    private fun updateVideoTransform(textureView: TextureView, container: ViewGroup) {
        val containerWidth = container.width
        val containerHeight = container.height
        if (containerWidth == 0 || containerHeight == 0) return

        val rotated = currentRotation == 90 || currentRotation == 270
        val videoAspect = if (rotated) {
            VIDEO_HEIGHT.toFloat() / VIDEO_WIDTH.toFloat()
        } else {
            VIDEO_WIDTH.toFloat() / VIDEO_HEIGHT.toFloat()
        }

        var viewWidth = containerWidth
        var viewHeight = (containerWidth / videoAspect).toInt()
        if (viewHeight > containerHeight) {
            viewHeight = containerHeight
            viewWidth = (containerHeight * videoAspect).toInt()
        }

        val params = textureView.layoutParams as FrameLayout.LayoutParams
        params.width = viewWidth
        params.height = viewHeight
        textureView.layoutParams = params

        // The layout pass triggered by the params change above hasn't happened
        // yet - textureView.width/height are still the OLD values here, so the
        // transform matrix (which needs the NEW size) has to wait for it.
        textureView.post {
            val matrix = Matrix()
            val w = textureView.width.toFloat()
            val h = textureView.height.toFloat()
            if (w == 0f || h == 0f) return@post
            when (currentRotation) {
                90, 270 -> {
                    val viewRect = RectF(0f, 0f, w, h)
                    val bufferRect = RectF(0f, 0f, h, w) // swapped - see class doc
                    bufferRect.offset(viewRect.centerX() - bufferRect.centerX(), viewRect.centerY() - bufferRect.centerY())
                    matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                    matrix.postRotate(currentRotation.toFloat(), viewRect.centerX(), viewRect.centerY())
                }
                180 -> matrix.postRotate(180f, w / 2f, h / 2f)
                else -> {} // identity - already the right shape, nothing to rotate
            }
            textureView.setTransform(matrix)
        }
    }

    override fun onDestroy() {
        inputManager.unregisterInputDeviceListener(inputDeviceListener)
        tickRunnable?.let { tickHandler.removeCallbacks(it) }
        controlLink.close()
        // Normally already a no-op here - onPause() (which always runs first) already
        // finalizes any in-progress recording - kept as a defensive backstop.
        stopRecording(::log)
        videoReceiver.stop()
        audioReceiver.stop()
        micSender.stop()
        super.onDestroy()
    }
}
