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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nu.bruijn.rcvehicle.R
import nu.bruijn.rcvehicle.control.ControlLink
import nu.bruijn.rcvehicle.video.VideoPacket
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

        // Must match VideoSender's defaults - the encoder's configured resolution,
        // which MediaCodec's decoder output is always sized to regardless of the
        // vehicle's current physical orientation (see VideoSender's doc: the raw
        // camera frames are sent as-is, only a display rotation hint travels
        // alongside them).
        private const val VIDEO_WIDTH = 640
        private const val VIDEO_HEIGHT = 480
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_controller_drive)

        val address = intent.getStringExtra(EXTRA_VEHICLE_ADDRESS)
        val logView = findViewById<TextView>(R.id.textRtcLog)
        val videoContainer = findViewById<FrameLayout>(R.id.videoContainer)
        val textureVideo = findViewById<TextureView>(R.id.textureVideo)

        fun log(line: String) = runOnUiThread {
            Log.d("RCDebug", line)
            logView.append(line + "\n")
        }

        videoReceiver.onError = { msg -> log("video: $msg") }
        videoReceiver.onRotationChanged = { rotation ->
            runOnUiThread {
                currentRotation = rotation
                updateVideoTransform(textureVideo, videoContainer)
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
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    controlLink.connect(address, ControlLink.PORT)
                    log("UDP control link connected to $address:${ControlLink.PORT}")
                } catch (e: Exception) {
                    log("connect failed: ${e::class.simpleName}: ${e.message}")
                }
            }
        }

        findViewById<CheckBox>(R.id.checkSteerLimit).setOnCheckedChangeListener { _, checked ->
            controlLink.sendSteerLimit(checked)
        }

        findViewById<Button>(R.id.buttonStop).setOnClickListener {
            steerX = 0f
            throttleY = 0f
            controlLink.send(0f, 0f, seq++)
        }

        findViewById<JoystickView>(R.id.joystickSteer).onMove = { x, _ -> steerX = x }
        // Touch y increases downward; up (forward throttle) must be positive, so negate.
        findViewById<JoystickView>(R.id.joystickThrottle).onMove = { _, y -> throttleY = -y }

        // Fixed ~33Hz tick, same reasoning as the vehicle-side debug sliders: a
        // steady heartbeat is what the vehicle-side failsafe is designed to key off.
        tickRunnable = object : Runnable {
            override fun run() {
                controlLink.send(steerX, throttleY, seq++)
                tickHandler.postDelayed(this, 30)
            }
        }
        tickHandler.post(tickRunnable!!)
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
        videoReceiver.stop()
        super.onDestroy()
    }
}
