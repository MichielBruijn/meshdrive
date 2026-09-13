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
import nu.bruijn.rcvehicle.control.ControlLink
import nu.bruijn.rcvehicle.mode.ModePrefs
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
        val logView = findViewById<TextView>(R.id.textRtcLog)
        val videoContainer = findViewById<FrameLayout>(R.id.videoContainer)
        val textureVideo = findViewById<TextureView>(R.id.textureVideo)
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

        fun log(line: String) = runOnUiThread {
            Log.d("RCDebug", line)
            logView.append(line + "\n")
        }

        fun updateDebugOverlay() {
            val fpsText = if (lastFps >= 0) String.format("%.0f", lastFps) else "--"
            val latencyText = if (lastLatencyMs >= 0) lastLatencyMs.toString() else "--"
            textDebugOverlay.text = "FPS: $fpsText | Latency: $latencyText ms"
        }

        fun setQuality(index: Int) {
            val clamped = index.coerceIn(0, VideoQuality.entries.size - 1)
            if (clamped == currentQualityIndex) return
            currentQualityIndex = clamped
            controlLink.sendQuality(clamped)
        }

        videoReceiver.onError = { msg -> log("video: $msg") }
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
                try {
                    videoReceiver.start(VideoPacket.PORT, Surface(surface), VIDEO_WIDTH, VIDEO_HEIGHT)
                    log("listening for video on port ${VideoPacket.PORT}")
                } catch (e: Exception) {
                    log("video listen failed: ${e::class.simpleName}: ${e.message}")
                }
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
                    controlLink.startPing { rtt ->
                        runOnUiThread {
                            lastLatencyMs = rtt
                            lastPongAtMs = System.currentTimeMillis()
                            updateDebugOverlay()
                        }
                    }
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

        val buttonRearView = findViewById<Button>(R.id.buttonRearView)
        val defaultRearViewTint = buttonRearView.backgroundTintList
        var rearViewActive = false
        buttonRearView.setOnClickListener {
            rearViewActive = !rearViewActive
            controlLink.sendCamera(rearViewActive)
            buttonRearView.text = getString(if (rearViewActive) R.string.controller_front_view else R.string.controller_rear_view)
            buttonRearView.backgroundTintList = if (rearViewActive) activeTint else defaultRearViewTint
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
        tickHandler.post(tickRunnable!!)
    }

    private var isRecording = false
    private var recordingDisplayPath: String? = null
    private var recordingUri: android.net.Uri? = null
    private var recordingPfd: android.os.ParcelFileDescriptor? = null

    /** Holds everything one recording attempt needs, whichever way it was created. */
    private class RecordingTarget(
        val muxer: android.media.MediaMuxer,
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
            return RecordingTarget(muxer, uri, pfd, "Movies/MeshDrive/$fileName")
        }

        val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: return null
        dir.mkdirs()
        val file = java.io.File(dir, fileName)
        val muxer = android.media.MediaMuxer(file.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        return RecordingTarget(muxer, null, null, file.absolutePath)
    }

    /**
     * Records the incoming video stream to an .mp4 (VideoReceiver.startRecording()/
     * VideoRecorder - remuxes the same H.264 access units already being decoded, no
     * re-encoding) - see createRecordingTarget() for where the file actually goes.
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
                videoReceiver.startRecording(target.muxer) { msg -> log("record: $msg") }
                recordingUri = target.uri
                recordingPfd = target.pfd
                recordingDisplayPath = target.displayPath
                isRecording = true
                buttonRecord.text = getString(R.string.controller_recording)
                log("recording to ${target.displayPath}")
            } else {
                val wroteAnything = videoReceiver.stopRecording()
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
                buttonRecord.text = getString(R.string.controller_record)
                log(if (wroteAnything) "recording saved: $recordingDisplayPath" else "recording discarded - no video was received")
            }
        }
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
        // videoReceiver.stop() (which stops the recorder, flushing the muxer's final
        // moov atom) must finish BEFORE the FileDescriptor it was writing through
        // gets closed below, or the file corrupts - order matters here.
        videoReceiver.stop()
        if (isRecording) {
            try {
                recordingPfd?.close()
            } catch (_: Exception) {
            }
            recordingUri?.let { uri ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    runCatching { contentResolver.update(uri, values, null, null) }
                }
            }
        }
        super.onDestroy()
    }
}
