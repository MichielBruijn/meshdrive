package nu.bruijn.rcvehicle.controller

import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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

        // The decoder is configured for VideoQuality.HIGH's dimensions regardless of
        // which preset the vehicle is actually encoding at right now: decoders can
        // freely handle an incoming SPS declaring a resolution *smaller* than what
        // they were configured for, but going *above* it is what typically requires
        // a reconfigure - so configuring at the largest preset up front means the
        // resolution picker/auto-quality loop below never needs to touch the
        // decoder, only the encoder (VideoSender.setQuality()). All three presets
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
    private val audioReceiver = AudioReceiver()
    private val tickHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null
    private var seq = 0
    private var currentRotation = 0

    // Latest joystick positions, read by the tick loop - not sent directly from
    // onMove itself, so the send rate stays a steady ~33Hz regardless of touch
    // event frequency (same reasoning as the vehicle-side debug sliders).
    private var steerX = 0f
    private var throttleY = 0f

    // Debug overlay state.
    private var lastFps = -1f
    private var lastLatencyMs = -1L
    private var telemetryEnabled = false
    private var lastTelemetry: ControlFrame.Decoded.Telemetry? = null

    // Connectivity liveness (always visible, not debug - see textConnectionWarning's doc):
    // lastPongAtMs is 0 until the very first reply, so "never connected" and "was
    // connected, now silent" read differently. Yggdrasil's own app dropping its VPN when
    // wifi turns off, with no automatic mobile-data fallback on some phones, is exactly
    // the kind of silent failure this needs to catch.
    private var lastPongAtMs = 0L
    private var connectStartedAtMs = 0L

    // Video quality: currentQualityIndex is what the vehicle is actually encoding
    // at (VideoQuality's ordinal); autoQuality toggles the FPS-driven step-down/
    // step-up loop in videoReceiver.onFps below. Manual button taps turn autoQuality
    // off, so a deliberate low-bandwidth choice never gets silently overridden.
    private var currentQualityIndex = VideoQuality.HIGH.ordinal
    private var autoQuality = false
    private var lowFpsStreak = 0
    private var healthyFpsStreak = 0

    // Promoted to fields (rather than onCreate-local vals) so onPause()/onResume()
    // can restart video/audio and log to the same views - see their doc for why
    // that's needed (screen lock doesn't reliably re-fire onSurfaceTextureAvailable).
    private lateinit var logView: TextView
    private lateinit var textureVideoView: TextureView
    private lateinit var videoContainerView: FrameLayout

    private fun log(line: String) = runOnUiThread {
        Log.d("RCDebug", line)
        logView.append(line + "\n")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_controller_drive)

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

        val address = intent.getStringExtra(EXTRA_VEHICLE_ADDRESS)
        logView = findViewById(R.id.textRtcLog)
        videoContainerView = findViewById(R.id.videoContainer)
        textureVideoView = findViewById(R.id.textureVideo)
        val videoContainer = videoContainerView
        val textureVideo = textureVideoView
        val textDebugOverlay = findViewById<TextView>(R.id.textDebugOverlay)
        val textConnectionWarning = findViewById<TextView>(R.id.textConnectionWarning)

        // Verbose log + FPS/latency numbers + the resolution picker + steer limit are
        // diagnostic/rarely-needed, hidden unless "Debug mode" is on (MainActivity) -
        // textConnectionWarning below stays visible regardless, that one's a safety
        // signal, not a diagnostic.
        val qualitySection = findViewById<View>(R.id.qualitySection)
        val buttonSteerLimitView = findViewById<View>(R.id.buttonSteerLimit)
        lifecycleScope.launch {
            val debugEnabled = ModePrefs(applicationContext).debugEnabled.first()
            logView.visibility = if (debugEnabled) View.VISIBLE else View.GONE
            textDebugOverlay.visibility = if (debugEnabled) View.VISIBLE else View.GONE
            qualitySection.visibility = if (debugEnabled) View.VISIBLE else View.GONE
            buttonSteerLimitView.visibility = if (debugEnabled) View.VISIBLE else View.GONE
        }

        fun updateDebugOverlay() {
            val fpsText = if (lastFps >= 0) String.format("%.0f", lastFps) else "--"
            val latencyText = if (lastLatencyMs >= 0) lastLatencyMs.toString() else "--"
            var text = "FPS: $fpsText | Latency: $latencyText ms"
            if (telemetryEnabled) {
                val t = lastTelemetry
                text += if (t == null || !t.hasFix) {
                    "\nGPS: no fix"
                } else {
                    "\n${String.format("%.1f", t.speedKmh)} km/h | ${String.format("%.5f", t.lat)}, ${String.format("%.5f", t.lon)}"
                }
            }
            textDebugOverlay.text = text
        }

        fun setQuality(index: Int) {
            val clamped = index.coerceIn(0, VideoQuality.entries.size - 1)
            if (clamped == currentQualityIndex) return
            currentQualityIndex = clamped
            controlLink.sendQuality(clamped)
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
                updateDebugOverlay()
                if (autoQuality) {
                    when {
                        fps < LOW_FPS_THRESHOLD -> {
                            lowFpsStreak++
                            healthyFpsStreak = 0
                            if (lowFpsStreak >= STEP_DOWN_AFTER_TICKS) {
                                setQuality(currentQualityIndex - 1)
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
                startVideo(surface)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                updateVideoTransform(textureVideo, videoContainer)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                videoReceiver.stop()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        if (address.isNullOrEmpty()) {
            log("no vehicle address given")
        } else {
            connectStartedAtMs = System.currentTimeMillis()
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    controlLink.connect(address, ControlLink.PORT)
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
                                updateDebugOverlay()
                            }
                        },
                    )
                } catch (e: Exception) {
                    log("connect failed: ${e::class.simpleName}: ${e.message}")
                }
            }
        }

        val activeTint = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2E7D32"))

        val buttonSteerLimit = findViewById<Button>(R.id.buttonSteerLimit)
        val defaultSteerLimitTint = buttonSteerLimit.backgroundTintList
        var steerLimitEnabled = false
        buttonSteerLimit.setOnClickListener {
            steerLimitEnabled = !steerLimitEnabled
            controlLink.sendSteerLimit(steerLimitEnabled)
            buttonSteerLimit.backgroundTintList = if (steerLimitEnabled) activeTint else defaultSteerLimitTint
        }

        val checkAutoQuality = findViewById<CheckBox>(R.id.checkAutoQuality)
        checkAutoQuality.setOnCheckedChangeListener { _, checked -> autoQuality = checked }
        findViewById<Button>(R.id.buttonQualityLow).setOnClickListener {
            checkAutoQuality.isChecked = false
            setQuality(VideoQuality.LOW.ordinal)
        }
        findViewById<Button>(R.id.buttonQualityMedium).setOnClickListener {
            checkAutoQuality.isChecked = false
            setQuality(VideoQuality.MEDIUM.ordinal)
        }
        findViewById<Button>(R.id.buttonQualityHigh).setOnClickListener {
            checkAutoQuality.isChecked = false
            setQuality(VideoQuality.HIGH.ordinal)
        }

        findViewById<CheckBox>(R.id.checkTelemetry).setOnCheckedChangeListener { _, checked ->
            telemetryEnabled = checked
            controlLink.sendTelemetryEnable(checked)
            if (!checked) lastTelemetry = null
            updateDebugOverlay()
        }

        val buttonRearView = findViewById<Button>(R.id.buttonRearView)
        val defaultRearViewTint = buttonRearView.backgroundTintList
        var rearViewActive = false
        buttonRearView.setOnClickListener {
            rearViewActive = !rearViewActive
            controlLink.sendCamera(rearViewActive)
            buttonRearView.text = getString(if (rearViewActive) R.string.controller_front_view else R.string.controller_rear_view)
            buttonRearView.backgroundTintList = if (rearViewActive) activeTint else defaultRearViewTint
        }

        val buttonMute = findViewById<Button>(R.id.buttonMute)
        val defaultMuteTint = buttonMute.backgroundTintList
        buttonMute.setOnClickListener {
            audioReceiver.muted = !audioReceiver.muted
            buttonMute.text = getString(if (audioReceiver.muted) R.string.controller_unmute else R.string.controller_mute)
            buttonMute.backgroundTintList = if (audioReceiver.muted) activeTint else defaultMuteTint
        }

        setUpRecordButton(::log)

        findViewById<SliderView>(R.id.sliderSteer).apply {
            vertical = false
            onMove = { x -> steerX = x }
        }
        // Touch y increases downward; up (forward throttle) must be positive, so negate.
        findViewById<SliderView>(R.id.sliderThrottle).apply {
            vertical = true
            onMove = { y -> throttleY = -y }
        }

        var lastWarningText: String? = null

        // Fixed ~33Hz tick, same reasoning as the vehicle-side debug sliders: a
        // steady heartbeat is what the vehicle-side failsafe is designed to key off.
        tickRunnable = object : Runnable {
            override fun run() {
                controlLink.send(steerX, throttleY, seq++)

                if (!address.isNullOrEmpty()) {
                    val now = System.currentTimeMillis()
                    val warning = when {
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
        videoReceiver.stop() // idempotent - safe even if it wasn't already running
        try {
            videoReceiver.start(VideoPacket.PORT, Surface(surface), VIDEO_WIDTH, VIDEO_HEIGHT)
            log("listening for video on port ${VideoPacket.PORT}")
        } catch (e: Exception) {
            log("video listen failed: ${e::class.simpleName}: ${e.message}")
        }
    }

    private fun startAudio() {
        audioReceiver.stop()
        try {
            audioReceiver.start(AudioPacket.PORT)
            log("listening for audio on port ${AudioPacket.PORT}")
        } catch (e: Exception) {
            log("audio listen failed: ${e::class.simpleName}: ${e.message}")
        }
    }

    /**
     * Locking the phone doesn't reliably tear down and recreate the TextureView's
     * Surface on every device - confirmed by direct report: video never came back
     * after unlocking, only a full app restart fixed it (onSurfaceTextureAvailable
     * simply never fired again). Rather than depend on that pairing, stop
     * everything explicitly here and restart it explicitly in onResume().
     *
     * Stopping the steering/throttle tick loop here matters as much as the video:
     * without it, the loop kept sending whatever steerX/throttleY a finger left on
     * a slider at the moment of locking, continuously, for as long as the phone
     * stayed locked - the vehicle's own failsafe (falls back to neutral after a
     * gap in control frames) never got a gap to trigger on. That stale, frozen
     * command - not the restart itself - is the more likely explanation for the
     * servo "jittering" once a restart finally reconnected things.
     */
    override fun onPause() {
        super.onPause()
        tickRunnable?.let { tickHandler.removeCallbacks(it) }
        // Finalizes the file properly instead of leaving it orphaned mid-recording -
        // videoReceiver.stop()/audioReceiver.stop() below would otherwise drop their
        // recorder references out from under an in-progress MuxerSession that never
        // gets told to actually finish (see stopRecording()'s doc).
        stopRecording(::log)
        videoReceiver.stop()
        audioReceiver.stop()
    }

    override fun onResume() {
        super.onResume()
        tickRunnable?.let { tickHandler.post(it) }
        startAudio()
        textureVideoView.surfaceTexture?.let { startVideo(it) }
    }

    private var isRecording = false
    private var recordingDisplayPath: String? = null
    private var recordingUri: android.net.Uri? = null
    private var recordingPfd: android.os.ParcelFileDescriptor? = null
    private var muxerSession: MuxerSession? = null

    /** Holds everything one recording attempt needs, whichever way it was created. */
    private class RecordingTarget(
        val session: MuxerSession,
        val uri: android.net.Uri?,
        val pfd: android.os.ParcelFileDescriptor?,
        val displayPath: String,
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
        val fileName = "meshdrive_" +
            java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".mp4"

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
            return RecordingTarget(MuxerSession(muxer), uri, pfd, "Movies/MeshDrive/$fileName")
        }

        val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: return null
        dir.mkdirs()
        val file = java.io.File(dir, fileName)
        val muxer = android.media.MediaMuxer(file.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        return RecordingTarget(MuxerSession(muxer), null, null, file.absolutePath)
    }

    /**
     * Records the incoming video (and, if it's actually flowing - see MuxerSession's
     * doc - audio) stream to an .mp4. VideoRecorder/AudioRecorderTrack remux the same
     * access units already being decoded, no re-encoding; see createRecordingTarget()
     * for where the file actually goes.
     */
    private fun setUpRecordButton(log: (String) -> Unit) {
        val buttonRecord = findViewById<Button>(R.id.buttonRecord)
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
                isRecording = true
                buttonRecord.text = getString(R.string.controller_recording)
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
        findViewById<Button>(R.id.buttonRecord).text = getString(R.string.controller_record)
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
        tickRunnable?.let { tickHandler.removeCallbacks(it) }
        controlLink.close()
        // Normally already a no-op here - onPause() (which always runs first) already
        // finalizes any in-progress recording - kept as a defensive backstop.
        stopRecording(::log)
        videoReceiver.stop()
        audioReceiver.stop()
        super.onDestroy()
    }
}
